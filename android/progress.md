# Android SDK — 进度日志

## 2026-07-22 — 多 PC 绑定与管理 Fragment 方案头脑风暴与 Spec 编写
- 完成多 PC 绑定的需求分析与架构设计。
- 确定以 PC 的固定 UUID 作为持久化 Key（`pairing_$uuid`）。
- 确定 Compose Material 2 风格内置管理 UI，适配宿主主题色，仅显示设备名。
- 完成详细设计文档 `docs/superpowers/specs/2026-07-22-multi-pc-pairing-design.md`。

## 2026-07-15 — SDK API 改进实现

### 实现内容（7 Tasks，全部完成）
- **Task 1**: SdkError 密封类 — 11 种子类型
- **Task 2**: PairingManager SharedPreferences 持久化 + baseKey HKDF 派生与存储 + random 生成
- **Task 3**: BleClient 权限内聚 — `hasPermissions()`/`getMissingPermissions()`，移除 `@Suppress`
- **Task 4**: BleNotificationSDK — `close()`/`getPairedDevices()`/`unpair()`/`startPairing(appName)`
- **Task 5**: FrameEncoder.encodeRegister 加 `random` 参数(base64)
- **Task 6**: SdkErrorTest + 测试编译修复
- **Task 7**: 文档更新

### 编译与测试
- Kotlin main source: BUILD SUCCESSFUL
- Test source: BUILD SUCCESSFUL
- 纯 JVM 测试: SdkErrorTest(2) / PairingManagerTest(3) / QrDecoderTest(10) 全 PASS
- Android 依赖/Native 依赖测试: BleClientTest(5 fail) / FrameEncoder NOTIFY(3 fail) / FrameDecoder NOTIFY(1 fail) — JVM 无框架，预存

### 接口变更
| 变更 | 说明 |
|------|------|
| `SdkError` | 新增密封类，11 种子类型，替换所有 `String onError` |
| `PairingManager(Context)` | 构造加 Context，SharedPreferences 持久化 |
| `startPairing(activity, appName, callback)` | 新增 appName 参数 |
| `getPairedDevices(): List<PairedDevice>` | 新增 |
| `unpair(packageName)` | 新增 |
| `close()` | 新增，断开连接 + 取消回调 + 标记关闭 |
| `BleClient.hasPermissions(context)` | 新增静态方法 |
| `BleClient.getMissingPermissions(context)` | 新增静态方法 |
| `encodeRegister(appName, packageName, random)` | 新增 random 参数(base64) |
