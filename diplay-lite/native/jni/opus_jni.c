/*
 * libopus 的 JNI 封装 —— 麦克风上行**编码器** + 导航/通话/语音**解码器**。
 *
 * 编码：输入 20ms 48kHz 单声道 16-bit 小端 PCM 帧，输出单个标准 Opus access unit。
 *       供无 MediaCodec OPUS 编码器的老设备（API < 29）兜底。
 * 解码：输入单个 Opus access unit，输出 16-bit 小端 PCM。
 *       供 API < 21 的老设备（如 C8815/API16，本机连 OMX.google.opus.decoder 都没有）兜底；
 *       没有它，导航播报、通话对方声音、Siri 全是静音（音乐走 AAC 不受影响）。
 *
 * 弱机（C8815 = MSM8625/Cortex-A5 双核）性能取舍：
 *   · 编码复杂度降到 2（libopus 默认 10，是全链路最费 CPU 的一环）；
 *   · 关 inband-FEC 与 DTX（CarPlay 官方链路本身有重传/冗余，不叠加）；
 *   · 声明 VOICE 信号（调优 SILK 侧参数）；
 *   · 解码输出缓冲区**在 create 时一次分配**，之后每包复用 ——
 *     不再每包 malloc/free（50 fps × 双向的堆操作会喂分配器与 GC，表现为处理耗时尖峰）。
 *
 * 注意：Kotlin 侧 companion object 里的 external fun 必须加 @JvmStatic，
 * 否则 native 方法注册在 Companion 类上，JNI 名会变成
 * ..._00024Companion_nativeCreate，与本文件导出的 ..._NativeOpusEncoder_nativeCreate
 * 不匹配 → UnsatisfiedLinkError（#27 在这上面丢过一轮验证）。
 */
#include <jni.h>
#include <opus.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define OPUS_JNI_MAX_PACKET 2000 /* libopus 单包上限 1275 字节，留余量 */
/* libopus 单帧上限 120ms：48kHz × 0.12s = 5760 采样/声道 */
#define OPUS_JNI_MAX_FRAME_SAMPLES 5760
#define OPUS_JNI_BYTES_PER_SAMPLE 2
/* 默认 10 太贵；本分支的目标机是没有 NEON 的 Cortex-A5 */
#define OPUS_JNI_ENCODER_COMPLEXITY 2

/* ------------------------------ 编码器 ------------------------------ */

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_NativeOpusEncoder_nativeCreate(
        JNIEnv *env, jclass clazz,
        jint sampleRate, jint channels, jint application, jint bitrate) {
    (void) env;
    (void) clazz;
    int error = OPUS_OK;
    OpusEncoder *encoder = opus_encoder_create(sampleRate, channels, application, &error);
    if (error != OPUS_OK || encoder == NULL) {
        return 0;
    }
    if (opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate)) != OPUS_OK) {
        opus_encoder_destroy(encoder);
        return 0;
    }
    /* 弱机调优：失败不致命，只是这次编码开销没降下来。 */
    opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(OPUS_JNI_ENCODER_COMPLEXITY));
    opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(0));
    opus_encoder_ctl(encoder, OPUS_SET_DTX(0));
    return (jlong) (intptr_t) encoder;
}

JNIEXPORT jbyteArray JNICALL
Java_com_shilapi_xcertplay_media_NativeOpusEncoder_nativeEncode(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray pcm, jint frameSamples) {
    (void) thiz;
    OpusEncoder *encoder = (OpusEncoder *) (intptr_t) handle;
    if (encoder == NULL || pcm == NULL || frameSamples <= 0) {
        return NULL;
    }
    jsize length = (*env)->GetArrayLength(env, pcm);
    if (length < frameSamples * OPUS_JNI_BYTES_PER_SAMPLE) {
        return NULL;
    }
    jbyte *input = (*env)->GetByteArrayElements(env, pcm, NULL);
    if (input == NULL) {
        return NULL;
    }
    unsigned char output[OPUS_JNI_MAX_PACKET];
    opus_int32 count = opus_encode(
            encoder,
            (const opus_int16 *) input,
            frameSamples,
            output,
            sizeof(output));
    (*env)->ReleaseByteArrayElements(env, pcm, input, JNI_ABORT);
    if (count <= 0) {
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, count);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, count, (jbyte *) output);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_NativeOpusEncoder_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    if (handle != 0) {
        opus_encoder_destroy((OpusEncoder *) (intptr_t) handle);
    }
}

/* ------------------------------ 解码器 ------------------------------ */

/* 句柄 = 本结构体指针：解码器 + 一块复用到底的输出缓冲。 */
typedef struct {
    OpusDecoder *decoder;
    unsigned char *output;
    size_t outputBytes;
} OpusDecoderHandle;

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_NativeOpusDecoder_nativeCreate(
        JNIEnv *env, jclass clazz, jint sampleRate, jint channels) {
    (void) env;
    (void) clazz;
    if (channels <= 0) {
        return 0;
    }
    int error = OPUS_OK;
    OpusDecoderHandle *handle = (OpusDecoderHandle *) malloc(sizeof(OpusDecoderHandle));
    if (handle == NULL) {
        return 0;
    }
    handle->decoder = opus_decoder_create(sampleRate, channels, &error);
    handle->output = NULL;
    handle->outputBytes = 0;
    if (error != OPUS_OK || handle->decoder == NULL) {
        free(handle);
        return 0;
    }
    handle->outputBytes = (size_t) OPUS_JNI_MAX_FRAME_SAMPLES * (size_t) channels *
                          OPUS_JNI_BYTES_PER_SAMPLE;
    handle->output = (unsigned char *) malloc(handle->outputBytes);
    if (handle->output == NULL) {
        opus_decoder_destroy(handle->decoder);
        free(handle);
        return 0;
    }
    return (jlong) (intptr_t) handle;
}

/*
 * 解一个 Opus access unit。maxSamples = 允许解码出的每声道采样数上限（CarPlay 20ms 帧 = 960）。
 * 返回实际解出的 PCM（长度 = 采样数 × channels × 2）；失败返回 NULL。
 */
JNIEXPORT jbyteArray JNICALL
Java_com_shilapi_xcertplay_media_NativeOpusDecoder_nativeDecode(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray packet,
        jint maxSamples, jint channels) {
    (void) thiz;
    OpusDecoderHandle *h = (OpusDecoderHandle *) (intptr_t) handle;
    if (h == NULL || h->decoder == NULL || h->output == NULL ||
        packet == NULL || channels <= 0 || maxSamples <= 0) {
        return NULL;
    }
    /* 夹在预分配容量内：MAX_FRAME_SAMPLES 已是 libopus 单帧上限，正常永远不会被夹。 */
    if (maxSamples > OPUS_JNI_MAX_FRAME_SAMPLES) {
        maxSamples = OPUS_JNI_MAX_FRAME_SAMPLES;
    }
    jsize length = (*env)->GetArrayLength(env, packet);
    if (length <= 0) {
        return NULL;
    }
    jbyte *input = (*env)->GetByteArrayElements(env, packet, NULL);
    if (input == NULL) {
        return NULL;
    }
    int decoded = opus_decode(
            h->decoder,
            (const unsigned char *) input,
            length,
            (opus_int16 *) h->output,
            maxSamples,
            0);
    (*env)->ReleaseByteArrayElements(env, packet, input, JNI_ABORT);
    if (decoded <= 0) {
        return NULL;
    }
    jsize resultBytes = (jsize) decoded * channels * OPUS_JNI_BYTES_PER_SAMPLE;
    if ((size_t) resultBytes > h->outputBytes) {
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, resultBytes);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, resultBytes, (jbyte *) h->output);
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_NativeOpusDecoder_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    OpusDecoderHandle *h = (OpusDecoderHandle *) (intptr_t) handle;
    if (h == NULL) {
        return;
    }
    if (h->decoder != NULL) {
        opus_decoder_destroy(h->decoder);
    }
    if (h->output != NULL) {
        free(h->output);
    }
    free(h);
}