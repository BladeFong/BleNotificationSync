# Demo App — Findings & Decisions

## Requirements
- 集成 `:sdk` 测试绑定/解绑/闹钟通知
- 非默认应用图标（勾选方框 VectorDrawable）
- 扫描绑定/解除绑定按钮（根据配对状态互斥置灰）
- EditText 默认通知文本
- 发送按钮设置 10 秒后闹钟后 finish()

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| 单 Activity | 5 个控件，无需多页面 |
| XML Layout | 项目规范，不用 Compose |
| AppCompat | Light.DarkActionBar 主题自带 Toolbar |
| VectorDrawable 图标 | 无尺寸适配问题，自适应 |
| `SCHEDULE_EXACT_ALARM` | Demo 用 setAlarmClock 不需要，但显式声明便于测试 |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| (none yet) | - |
