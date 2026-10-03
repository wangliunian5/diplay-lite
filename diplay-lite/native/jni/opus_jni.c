/*
 * libopus 的 JNI 封装 —— 麦克风上行**编码器** + 导航/通话/语音**解码器**。
 *
 * 编码：输入 20ms 48kHz 单声道 16-bit 小端 PCM 帧，输出单个标准 Opus access unit。
 *       供无 MediaCodec OPUS 编码器的老设备（API < 29）兜底。
 * 解码：输入单个 Opus access unit，输出 16-bit 小端 PCM。
 *       供 API < 21 的老设备（如 C8815/API16，本机连 OMX.google.opus.decoder 都没有）兜底；
 *       没有它，导航播报、通话对方声音、Siri 全是静音（音乐走 AAC 不受影响）。
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

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_NativeOpusDecoder_nativeCreate(
        JNIEnv *env, jclass clazz, jint sampleRate, jint channels) {
    (void) env;
    (void) clazz;
    int error = OPUS_OK;
    OpusDecoder *decoder = opus_decoder_create(sampleRate, channels, &error);
    if (error != OPUS_OK || decoder == NULL) {
        return 0;
    }
    return (jlong) (intptr_t) decoder;
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
    OpusDecoder *decoder = (OpusDecoder *) (intptr_t) handle;
    if (decoder == NULL || packet == NULL || channels <= 0 || maxSamples <= 0) {
        return NULL;
    }
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
    size_t outputBytes = (size_t) maxSamples * (size_t) channels * OPUS_JNI_BYTES_PER_SAMPLE;
    unsigned char *output = (unsigned char *) malloc(outputBytes);
    if (output == NULL) {
        (*env)->ReleaseByteArrayElements(env, packet, input, JNI_ABORT);
        return NULL;
    }
    int decoded = opus_decode(
            decoder,
            (const unsigned char *) input,
            length,
            (opus_int16 *) output,
            maxSamples,
            0);
    (*env)->ReleaseByteArrayElements(env, packet, input, JNI_ABORT);
    if (decoded <= 0) {
        free(output);
        return NULL;
    }
    jsize resultBytes = (jsize) decoded * channels * OPUS_JNI_BYTES_PER_SAMPLE;
    jbyteArray result = (*env)->NewByteArray(env, resultBytes);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, resultBytes, (jbyte *) output);
    }
    free(output);
    return result;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_NativeOpusDecoder_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    if (handle != 0) {
        opus_decoder_destroy((OpusDecoder *) (intptr_t) handle);
    }
}