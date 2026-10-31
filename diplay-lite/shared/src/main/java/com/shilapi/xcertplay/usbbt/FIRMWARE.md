# 外置 USB 蓝牙固件

DiPlay 随 APK 打包 Realtek USB 固件和配置，不需要用户手动下载。
固件加载在后台初始化阶段执行；即使 HCI Reset 成功，也会检查控制器是否处于需要补丁的 ROM 版本。

## 自动匹配范围

匹配依据是控制器 Read Local Version 返回的 Realtek 厂商编号 0x005D（93）、LMP 子版本、HCI 修订号和 HCI 版本。
品牌和 VID:PID 只作为识别提示，不决定使用哪一份 Realtek 补丁，因此同芯片的转售设备也能匹配。
RTL8761CU 的 HCI 版本按上游规则使用通配，其余字段必须匹配。

| USB 芯片配置 | LMP 子版本 | HCI 修订号 | HCI 版本 | 固件格式 | 配置要求 |
| --- | --- | --- | --- | --- | --- |
| RTL8723A | 1200 | 000b | 06 | 原始镜像 | 无 |
| RTL8723B | 8723 | 000b | 06 | Realtech | 可选，已打包 |
| RTL8723D | 8723 | 000d | 08 | Realtech | 必需，已打包 |
| RTL8761A | 8761 | 000a | 06 | Realtech | 可选，已打包 |
| RTL8761BU（包括匹配该身份的 CM390/BUV） | 8761 | 000b | 0a | Realtech | 可选，已打包 |
| RTL8761CU | 8761 | 000e | 通配 | Realtech | 可选，已打包 |
| RTL8821A | 8821 | 000a | 06 | Realtech | 可选，已打包 |
| RTL8821C | 8821 | 000c | 08 | Realtech | 可选，已打包 |
| RTL8822B | 8822 | 000b | 07 | Realtech | 必需，已打包 |
| RTL8822CU | 8822 | 000c | 0a | RTBTCore | 可选，已打包 |
| RTL8852AU | 8852 | 000a | 0b | RTBTCore | 可选，已打包 |
| RTL8852BU | 8852 | 000b | 0b | RTBTCore | 可选，已打包 |
| RTL8852CU | 8852 | 000c | 0c | RTBTCore v2 镜像 | 可选，已打包 |
| RTL8851BU | 8851 | 000b | 0c | RTBTCore | 可选，已打包 |
| RTL8922AU | 8922 | 000a | 0c | RTBTCore | 可选，已打包 |
| RTL8852BTU | 8852 | 0087 | 0c | RTBTCore | 可选，已打包 |

表中十六进制数未加 0x 前缀。USB BU/CU 固件与 UART B/BS/CS/DS 固件不能互换。
TP-Link UB500、ASUS USB-BT500、绿联 CM390 等型号可能有硬件修订；只有实际返回上述身份的设备才采用对应配置。
这是一份软件匹配和打包清单，不代表这些品牌的所有硬件修订均已真机验证。

## 加载和保护

- Realtech 容器验证扩展签名和项目编号，读取 ROM ECO，只选择该 ECO 的补丁段，替换补丁版本尾部后附加匹配的配置。
- RTBTCore 容器校验节与子节边界，按 ECO、安全密钥编号和优先级选择 snippets/dummy/security 数据。读取安全密钥使用 0xfc61；非零密钥的设备不附加普通配置文件。
- 通过 0xfc20 发送每片最多 252 字节的数据。序号从 0 开始，127 后回到 1；末片带 0x80 标记，整倍数长度以空末片结束。每片检查状态、回包长度及序号。
- 不发送空的 0xfc20“进入下载模式”命令，不把整个 Realtech/RTBTCore 容器当作原始镜像发送。
- 上传后检查本地版本已离开原 ROM 身份，再复位并继续地址、缓冲区、名称和可发现性初始化。上传失败会停止初始化，保留具体失败原因。
- 对未知或已经打过补丁的身份跳过上传。缺失必需固件、配置损坏、项目不符或 ECO 不匹配时明确失败，不回退到另一芯片的文件。

## 固件来源、完整性和许可

二进制来自 [linux-firmware 官方仓库](https://gitlab.com/kernel-firmware/linux-firmware)，固定提交：
`38c1a7096dfb2188f55f342b835f14c362c59409`。

`shared/src/main/assets/usbbt_fw/manifest.json` 列出 31 个固件/配置文件的资源路径、上游路径、大小和 SHA-256，总计 986,974 字节。
上游 WHENCE 声明的配置别名已解析为实际二进制文件，避免 Windows 检出符号链接后错误打包链接文本。
原有 `0bda_8771.bin` 已移至 `rtl_bt/rtl8761bu_fw.bin`，内容相同。

Realtek 许可全文随 APK 放在 `usbbt_fw/LICENCE.rtlwifi_firmware.txt`，允许按其条件再分发未经修改的固件二进制。
仓库中的固件文件保持上游原字节；运行时的容器选择和下载数据组装属于设备初始化流程。

协议和芯片映射核对来源：

- [Bluetooth SIG Company Identifiers](https://www.bluetooth.com/specifications/assigned-numbers/)
- [Linux btrtl.c](https://github.com/torvalds/linux/blob/master/drivers/bluetooth/btrtl.c)
- [Linux btrtl.h](https://github.com/torvalds/linux/blob/master/drivers/bluetooth/btrtl.h)
- [linux-firmware WHENCE](https://gitlab.com/kernel-firmware/linux-firmware/-/blob/38c1a7096dfb2188f55f342b835f14c362c59409/WHENCE)
- [Realtek 固件许可](https://gitlab.com/kernel-firmware/linux-firmware/-/blob/38c1a7096dfb2188f55f342b835f14c362c59409/LICENSES/LICENCE.rtlwifi_firmware.txt)

## 本地覆盖与其他厂商

构建前可以在 `shared/src/main/assets/usbbt_fw/` 放入设备专用覆盖：

```text
<vid>_<pid>.bin         Realtek 完整容器或 RTL8723A 原始镜像
<vid>_<pid>_config.bin  Realtek 对应设备配置
<vid>_<pid>.hcd         Broadcom 对应设备的 HCD 命令记录
```

VID/PID 使用小写四位十六进制。Realtek 覆盖文件仍必须通过芯片项目和 ECO 校验。
Broadcom 只使用已知 Broadcom 设备的精确 VID:PID 文件；本次未打包无法核实具体设备来源与许可的第三方 HCD 集合。
不能用通用 BCM20702 文件替代所有 Broadcom 修订。

CSR8510 / TP-Link UB400 保留 ROM 初始化路径。绿联 CM591 / Actions 控制器不再因绿联 VID 被误判成 Realtek。
Intel、Atheros、MediaTek、Marvell 等有独立下载协议；本实现没有为它们新增固件下载支持。
设备能被 USB 枚举或通过基础 HCI 自检，并不等于它的固件下载、配对、RFCOMM 或 CarPlay 全链路已经可用。

## 验证范围与实机回归

自动测试覆盖全部打包配置和可匹配的 ECO 补丁、两种容器格式、密钥/优先级选择、错误文件拒绝、分片边界与序号回绕、来源哈希，以及 CM390 的 USB/HCI 初始化模拟。
模拟测试不能替代真实 CM390、车机 USB Host、供电、配对和 iPhone CarPlay 验证。

实机优先使用用户现有 CM390：插入并授权 USB，检查日志中的型号、ROM、资源名、配置长度、分片数和上传后版本；随后测试首次配对、断开重连和无线 CarPlay。
若设备不匹配，保留 VID:PID、Read Local Version、ROM ECO 和失败命令的日志，再为其增加独立配置，勿通过修改 VID 白名单强行加载其他芯片固件。
