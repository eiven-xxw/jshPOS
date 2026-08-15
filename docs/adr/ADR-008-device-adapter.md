# ADR-008：Android 设备适配与插件治理

- 状态：Accepted
- 日期：2026-08-15

Flutter只依赖 `pos_device_adapter` 的稳定Dart接口；Kotlin插件负责厂商SDK、AIDL、USB、蓝牙和LAN差异。每个厂商实现必须声明能力、超时、错误码、线程和恢复语义，并提供Fake实现。商业硬件至少达到L2认证。
