# Findings & Decisions

## Requirements
- Android → Windows/macOS/Linux BLE 闹钟通知同步
- 零云端、零账号、纯本地
- 二维码配对，无需 OS 蓝牙配对
- 每次通知独立连接，用完即断
- 支持 APP 图标传输
- AES-GCM 加密（Android 用 LibTomCrypt JNI，桌面用 Rust 原生 crate）

## Research Findings
- BLE GATT 连接耗时约 300ms - 1 秒
- MTU 协商后有效载荷约 244 字节
- 图标 5KB ≈ 21 分片，50KB ≈ 205 分片
- Windows Toast 通知支持图标
- macOS UserNotifications 支持图标
- BLE 广播包最多 31 字节，放不下图标
- Kotlin 和 Java 性能基本一致（都跑在 JVM 上）
- LibTomCrypt 支持 AES-CCM/AES-GCM，跨平台 C 库（仅 Android JNI 使用）
- AES-GCM 加密后密文比明文长 tag 长度（16 字节）

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| Service UUID: 0000A1B2-... | 自定义基础 UUID，避免冲突 |
| WRITE_NO_RESPONSE | 提升吞吐，应用层做校验 |
| 分片协议: Magic+MsgType+Seq+TotalSeq+Payload | 简单高效，支持大数据传输 |
| 图标在绑定时传输 | 一次性传输，后续通知复用 |
| 独立 GATT Characteristic | 简化服务定义 |
| AES-GCM 加密 | 认证加密，防篡改；Android 用 LibTomCrypt，桌面用 Rust aes-gcm crate（已从 CCM 迁移） |
| 桌面端 Tauri v2 (Rust + Web) | 跨平台统一方案，替代 C# WinForms + Swift 独立实现 |
| 密钥按包名管理 | 绑定时生成，APP 和 GATT Server 分别存储 |
| 扫码三层设计 | 解码层/相机层/UI 层，APP 灵活选择 |
| Android minSdk API 23 | 覆盖 99.2% 设备，避开 Camera2 早期 bug |
| 图标二进制直传 | 不 Base64，省 33% 带宽 |
| 图标最大 60KB | 确保 255 帧内完成传输 |

## Skills Found
| Skill | 安装量 | 用途 |
|-------|--------|------|
| dpearson2699/swift-ios-skills@core-bluetooth | 2.3K | CoreBluetooth (macOS) |
| jeffallan/claude-skills@kotlin-specialist | 3.7K | Kotlin 开发 |
| novotnyllc/dotnet-artisan@dotnet-csharp | 287 | .NET/C# |
| jcurbelo/skills@wpf-best-practices | 364 | WPF |

## Technical Decisions (追加)
| Decision | Rationale |
|----------|-----------|
| 桌面端迁移到 Tauri v2 | 跨平台统一（Windows/macOS/Linux），Rust 后端复用加密生态，Web 前端快速迭代 |
| 桌面端使用 Rust 原生加密 crate | 替代 LibTomCrypt 桌面集成，减少 C 编译依赖，与 Tauri Rust 后端一致 |
| 密钥 = HKDF(package+random) | 配对时 Android 生成 32B 随机数，双方 HKDF(package+random)→baseKey 持久化。防止反编译包名推算密钥 |
| baseKey 持久化 + nonce 每次生成 | 双层随机：配对级隔离 + 消息级隔离 |
| AES-GCM（原设计） | 对齐桌面 Rust aes-gcm crate，替代原 CCM 实现 |
| HKDF info="" | 对齐桌面 Rust pk.expand(b"") |
| SdkError 密封类替换 String | 调用方可按类型分支处理 |
| 限制 WinRT 代理作用域防止跨 await | 对非 `Send` 的 WinRT 委托代理类型 `TypedEventHandler` 包裹局部作用域以使其在 await 点前被 drop，从而使 async block 符合 `Send` 约束并成功通过编译。 |
| 消息帧字段对齐 | 将桌面端解码的 NotifyData 结构体 field 从 `content` 改为 `body`，完全对齐 Android 端 SDK 构造 of `body` 字段，解决了通知反序列化失败的 Bug。 |
| Windows 凭据命令规范与C#互操作读取 | 修复 `cmdkey` 使用冒号连接参数及使用 `/generic` 创建通用凭据以确保密钥保存成功。并在 PowerShell 中采用 C# 互操作调用原生 `CredReadW` 接口读取密码，避开了对非系统内置的 `Get-StoredCredential` cmdlet 的依赖。 |
| Tauri 官方通知插件与权限校验 | 使用 Tauri v2 官方提供的 `tauri-plugin-notification` 跨平台插件，并在 Rust 异步任务中执行权限检测与动态获取，同时将插件报错引流至前端 UI 终端，以完美兼容多平台通知生命周期。 |

## Resources
- 设计文档: `docs/superpowers/specs/2026-07-10-ble-notification-sync-design.md`
- SDK 改进设计: `docs/superpowers/specs/2026-07-15-sdk-api-improvements-design.md`
- SDK 改进计划: `docs/superpowers/plans/2026-07-15-sdk-api-improvements.md`
- 原始方案: `docs/reference/original-scheme.md`
- 旧 Windows C# 实现（已废弃）: `windows/`

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| Windows 自定义广播创建时报参数错误 `0x80070057` | 改为使用默认构造函数 `new()` 创建发布器，然后在拿到的 `Advertisement` 实例中追加 `ServiceUuids`。这与 C# 实现完全对齐，避开了 `Create(&adv)` 在部分硬件/环境下的参数校验缺陷。 |
| Windows 广播不携带 Service UUID 导致客户端搜索不到 | 配合 `GattServiceProvider` 再启动一个独立的 `BluetoothLEAdvertisementPublisher`，专门在广告数据包中填充 `ServiceUuids`，使外设扫描过滤能正确发现该设备。 |
| WinRT 代理 `TypedEventHandler` 非 `Send` 导致 `tokio::select!` Await 点编译失败 | 将 `TypedEventHandler` 的创建与注册包裹在局部 block 内，确保该变量在随后的 await point (如 `select!`) 前即被析构（Drop），消除跨 Await 的非 Send 变量限制。 |
| Android 12+ BLE 扫描结果被静默屏蔽 | 由于 Android 12+ 对未声明 `neverForLocation` 的蓝牙扫描有严格的动态位置权限与 GPS 开关校验，导致无过滤扫描结果全部被底层屏蔽。由 Android 端重新申请并获取 `ACCESS_FINE_LOCATION` 与 `BLUETOOTH_SCAN` 权限解决。 |
| 桌面端接收到通知数据但未显示系统通知 | 原因在于双端 JSON 字段名称对齐冲突：Android 发送 `"body"` 键，而桌面 Rust 预期解密出 `"content"` 键。通过修改桌面端的 `NotifyData` 为 `body: String` 解决。 |
| Windows 凭据管理器保存与读取默默失败 | 1. 之前使用 `cmdkey /add` 参数不规范，导致系统返回 exit code 1 写入失败。更正为冒号与 `/generic` 参数。 2. 原 `Get-StoredCredential` cmdlet 在原生 Win10/11 系统中默认未安装，执行抛出 CommandNotFoundException。重构为通过 PowerShell 的 Add-Type 编译 C# 代码，调用 Windows 原生 `CredReadW` API 进行读取。 |
| Win10/11 开发环境下现代 Toast 通知被系统拦截 | Windows 10/11 对 WinRT/UWP 现代通知有硬性安全限制：应用必须在开始菜单（Start Menu）中拥有快捷方式并注册了匹配的 `AppUserModelId` (例如 `com.ble-notification-sync.desktop`)，否则通知会被系统静默丢弃。该问题在通过 MSI / NSIS 安装包安装该应用后会由安装器自动注册并完美显示；在开发调试阶段（`tauri dev`），开发者可通过手动为编译出的 EXE 在 `AppData\Roaming\Microsoft\Windows\Start Menu\Programs` 创建快捷方式并分配 ID 来临时绕过。 |
