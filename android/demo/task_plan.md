# Demo App — Task Plan

## Goal
创建 `:demo` Android application 模块，集成 `:sdk`，实现扫码绑定/解除绑定/10 秒闹钟通知的验证流程。

## Phases

### Phase 1: Demo App 实现
- [x] 设计文档
- [x] 实现计划
- [x] Task 1: Settings + Build 配置
- [x] Task 2: AndroidManifest.xml
- [x] Task 3: 图标 VectorDrawable + Adaptive Icon
- [x] Task 4: 字符串资源
- [x] Task 5: Layout activity_main.xml
- [x] Task 6: MainActivity.kt
- [x] Task 7: 全量编译验证
- **Status:** complete

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| 单 Activity + XML Layout | 最简单，够用，不用 Compose |
| 图标：勾选方框 | 辨识度最高的 todo 类图标 |
| `setReminder` 后 `finish()` | 验证 AlarmReceiver 跨进程触发 |
| 依赖 `project(":sdk")` | 同仓库直接引用 |
