# Findings & Decisions

## Requirements
- Android → Windows/macOS BLE 闹钟通知同步
- 零云端、零账号、纯本地
- 二维码配对，无需 OS 蓝牙配对
- 每次通知独立连接，用完即断
- 支持 APP 图标传输
- AES-CCM 加密，LibTomCrypt 跨平台

## Research Findings
- BLE GATT 连接耗时约 300ms - 1 秒
- MTU 协商后有效载荷约 244 字节
- 图标 5KB ≈ 21 分片，50KB ≈ 205 分片
- Windows Toast 通知支持图标
- macOS UserNotifications 支持图标
- BLE 广播包最多 31 字节，放不下图标
- Kotlin 和 Java 性能基本一致（都跑在 JVM 上）
- LibTomCrypt 支持 AES-CCM，跨平台 C 库
- AES-CCM 加密后密文比明文长 tag 长度（16 字节）

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| Service UUID: 0000A1B2-... | 自定义基础 UUID，避免冲突 |
| WRITE_NO_RESPONSE | 提升吞吐，应用层做校验 |
| 分片协议: Magic+MsgType+Seq+TotalSeq+Payload | 简单高效，支持大数据传输 |
| 图标在绑定时传输 | 一次性传输，后续通知复用 |
| 独立 GATT Characteristic | 简化服务定义 |
| AES-CCM 加密 | 认证加密，防篡改，LibTomCrypt 支持 |
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

## Resources
- 设计文档: `docs/superpowers/specs/2026-07-10-ble-notification-sync-design.md`
- 原始方案: `docs/reference/original-scheme.md`
- JustNow 项目参考: `/mnt/androiddev/StudioProjects/JustNow`

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| (none yet) | - |
