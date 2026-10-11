# DiPlay Lite

基于开源项目 DiPlay（© shihabal3amri，GPL-3.0）的社区分支，
面向老款安卓车机（API 16+）的兼容适配版本。

- 上游项目：https://github.com/shihabal3amri/DiPlay
- 本分支修改：老车机兼容适配（API16+）、设置界面重构、系统诊断取证增强、
  低配设备视频性能优化
- 许可证：GPL-3.0（详见 LICENSE）；本分支为社区维护，非上游官方版本
- native/ 内含 Opus 编解码器（BSD 许可，详见 native/jni/opus/COPYING）
- 车辆品牌标志版权归各权利人，仅供车主个性化识别；CarPlay 为 Apple Inc. 商标，
  本项目与上述各方无关联、无背书

## 构建

- Android Studio 或命令行 `gradlew assembleRelease`（需 JDK 17）
- `native/` 为 Opus JNI 源码（NDK 预编译产物随仓库分发）
- **注意**：`app/src/main/assets/offline-mfi/`（MFi 认证身份资产）不随本仓库公开，
  需自行准备后放入该目录，否则 CarPlay 认证无法通过（代码可正常编译）
