// vc101（T3）：借鉴 legacy-ref usb_host_compat.c——usbfs ioctl 原子断驱动 claim。
// USBDEVFS_DISCONNECT_CLAIM 在内核 device lock 下一步完成「断开 cdc_ncm 绑定 + claim」，
// 消除 sysfs unbind 后 67ms re-probe 抢绑的竞速窗口（T3 dmesg 实锤必败路径）。
// 全程走 App 已授权的 usbfs fd（UsbDeviceConnection.fileDescriptor），无需 root。
// 只在 API<=P 路径调用（Kotlin 侧护栏）；minSdk 16，纯 C 无 libc++ 依赖。
#include <jni.h>
#include <errno.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <linux/usbdevice_fs.h>

/* forceClaim: DISCONNECT_CLAIM 原子断驱动+claim；老内核无该 ioctl 时退化为
 * DISCONNECT（踢驱动）+ CLAIMINTERFACE 两步（仍有竞速窗口，但好过裸 claim）。 */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_forceClaim(
        JNIEnv *env, jobject self, jint file_descriptor, jint interface_id) {
    (void) env;
    (void) self;

    if (file_descriptor < 0 || interface_id < 0) return EINVAL;

#ifdef USBDEVFS_DISCONNECT_CLAIM
    struct usbdevfs_disconnect_claim disconnect_claim;
    memset(&disconnect_claim, 0, sizeof(disconnect_claim));
    disconnect_claim.interface = (unsigned int) interface_id;
    disconnect_claim.flags = 0;
    if (ioctl(file_descriptor, USBDEVFS_DISCONNECT_CLAIM, &disconnect_claim) == 0) {
        return 0;
    }
#endif

#ifdef USBDEVFS_DISCONNECT
    struct usbdevfs_ioctl disconnect;
    memset(&disconnect, 0, sizeof(disconnect));
    disconnect.ifno = interface_id;
    disconnect.ioctl_code = USBDEVFS_DISCONNECT;
    disconnect.data = NULL;
    (void) ioctl(file_descriptor, USBDEVFS_IOCTL, &disconnect);
#endif

    unsigned int iface = (unsigned int) interface_id;
    if (ioctl(file_descriptor, USBDEVFS_CLAIMINTERFACE, &iface) == 0) {
        return 0;
    }
    return errno != 0 ? errno : EIO;
}

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_setInterface(
        JNIEnv *env, jobject self, jint file_descriptor, jint interface_id, jint alternate_setting) {
    (void) env;
    (void) self;

    if (file_descriptor < 0 || interface_id < 0 || alternate_setting < 0) return EINVAL;

    struct usbdevfs_setinterface set_interface;
    memset(&set_interface, 0, sizeof(set_interface));
    set_interface.interface = (unsigned int) interface_id;
    set_interface.altsetting = (unsigned int) alternate_setting;

    if (ioctl(file_descriptor, USBDEVFS_SETINTERFACE, &set_interface) == 0) {
        return 0;
    }
    return errno != 0 ? errno : EIO;
}

/* submitUrbProbe: vc103f（T3）——向端点地址直接提交 1 字节 bulk IN 探测 URB，
 * 验证内核端点表是否可用（绕过接口/alt 概念，usbfs 按端点地址校验）。
 * 返回 0 = 内核接受了 URB（端点活着；设备 NAK 无数据按 DISCARD 收尾也归 0），
 * >0 = SUBMITURB 时的内核拒绝码（EINVAL/ENOENT = 端点表残缺实锤）。 */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_submitUrbProbe(
        JNIEnv *env, jobject self, jint file_descriptor, jint endpoint_address) {
    (void) env;
    (void) self;

    if (file_descriptor < 0 || endpoint_address <= 0) return EINVAL;

    struct usbdevfs_urb urb;
    memset(&urb, 0, sizeof(urb));
    unsigned char buffer[1];
    urb.type = USBDEVFS_URB_TYPE_BULK;
    urb.endpoint = (unsigned int) endpoint_address;
    urb.buffer = buffer;
    urb.buffer_length = sizeof(buffer);

    if (ioctl(file_descriptor, USBDEVFS_SUBMITURB, &urb) != 0) {
        return errno != 0 ? errno : EIO;
    }

    /* URB 已被内核接受——端点表可用。短轮询等待（设备可能 NAK 无数据），
     * 超时即 DISCARD 收尾；无论完成与否，只要 SUBMITURB 成功就归 0。 */
#ifndef USBDEVFS_REAPURBNDELAY
#define USBDEVFS_REAPURBNDELAY _IOW('U', 13, void *)
#endif
    struct usbdevfs_urb *reaped = NULL;
    int attempts = 10; /* 10 x 50ms = 500ms 上限 */
    while (attempts-- > 0) {
        reaped = NULL;
        if (ioctl(file_descriptor, USBDEVFS_REAPURBNDELAY, &reaped) == 0 && reaped != NULL) {
            return 0;
        }
        if (errno != EAGAIN) break;
        usleep(50 * 1000);
    }
#ifdef USBDEVFS_DISCARDURB
    (void) ioctl(file_descriptor, USBDEVFS_DISCARDURB, &urb);
    reaped = NULL;
    (void) ioctl(file_descriptor, USBDEVFS_REAPURBNDELAY, &reaped);
#endif
    return 0;
}

/* releaseInterface: vc103l（T3）——USBDEVFS_RELEASEINTERFACE 释放本 fd 对接口的
 * usbfs claim。vc103k 实测：KK proc_setconfig 在设备上有 usbfs claim（usbmux=1
 * 由调用方 claim、control=2 本函数早前 claim）时拒绝切配置，恒返 EBUSY=16。
 * 配置舞前先释放已知 claim，舞后重 claim。返回 0 成功，否则 errno。 */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_releaseInterface(
        JNIEnv *env, jobject self, jint file_descriptor, jint interface_number) {
    (void) env;
    (void) self;

    if (file_descriptor < 0) return EINVAL;
    unsigned int iface = (unsigned int) interface_number;
    if (ioctl(file_descriptor, USBDEVFS_RELEASEINTERFACE, &iface) == 0) {
        return 0;
    }
    return errno != 0 ? errno : EIO;
}

/* portReset: vc103g（T3 终审）——USBDEVFS_RESET 强制内核完全重枚举设备。
 * 用途：sunxi KK 枚举期竞速半解析配置描述符（接口表/端点表残缺）的唯一修复。
 * RESET 会清 usbfs claim 位图并触发内核重新绑定驱动——调用方须随后重开连接
 * 并重新 claim。返回 0 成功，否则 errno。 */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_portReset(
        JNIEnv *env, jobject self, jint file_descriptor) {
    (void) env;
    (void) self;

    if (file_descriptor < 0) return EINVAL;
    if (ioctl(file_descriptor, USBDEVFS_RESET) == 0) {
        return 0;
    }
    return errno != 0 ? errno : EIO;
}

/* setConfiguration: USBDEVFS_SETCONFIGURATION 切配置。EBUSY（接口被内核驱动绑定）
 * 时逐接口 GETDRIVER 查占用者：仅踢内核驱动（不碰 usbfs 用户态占用者），切完
 * CONFIGURATION 后不回接（CarPlay 会话期间由 App 持有，restore 无意义）。 */
JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_transport_LegacyUsbHostNative_setConfiguration(
        JNIEnv *env, jobject self, jint file_descriptor, jint configuration_id) {
    (void) env;
    (void) self;

    if (file_descriptor < 0 || configuration_id < 0) return EINVAL;

    unsigned int configuration = (unsigned int) configuration_id;
    if (ioctl(file_descriptor, USBDEVFS_SETCONFIGURATION, &configuration) == 0) {
        return 0;
    }
    int result = errno != 0 ? errno : EIO;
    if (result != EBUSY) return result;

#ifdef USBDEVFS_GETDRIVER
    for (unsigned int iface = 0; iface < 256; iface++) {
        struct usbdevfs_getdriver driver;
        memset(&driver, 0, sizeof(driver));
        driver.interface = iface;
        if (ioctl(file_descriptor, USBDEVFS_GETDRIVER, &driver) != 0) continue;
        if (strncmp(driver.driver, "usbfs", sizeof(driver.driver)) == 0) {
            return EBUSY;
        }
#ifdef USBDEVFS_DISCONNECT
        struct usbdevfs_ioctl disconnect;
        memset(&disconnect, 0, sizeof(disconnect));
        disconnect.ifno = (int) iface;
        disconnect.ioctl_code = USBDEVFS_DISCONNECT;
        if (ioctl(file_descriptor, USBDEVFS_IOCTL, &disconnect) != 0) {
            return errno != 0 ? errno : EIO;
        }
#endif
    }
    if (ioctl(file_descriptor, USBDEVFS_SETCONFIGURATION, &configuration) == 0) {
        return 0;
    }
    return errno != 0 ? errno : EIO;
#else
    return result;
#endif
}
