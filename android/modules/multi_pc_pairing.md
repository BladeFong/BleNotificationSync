# 阶段规划、决策记录 （拆分自 task_plan.md）

## 定位与功能描述
多 PC 绑定与内置设备管理组件模块。支持 Android SDK 关联多台 PC 设备，提供以 UUID 为唯一标识的持久化存储，以及 Jetpack Compose Material 2 风格的设备管理 Fragment/Activity。

---

# 研究发现、技术决策、需求分析 （拆分自 findings.md）

## 技术决策与踩坑记录
- **放弃随机 MAC 做主键**：BLE GATT 获得的 MAC 地址由于 privacy 机制存在随机化（RPA），频繁改变，不可作为设备持久化主键。改成 PC 二维码中的固定 `uuid` 作为 Key (`pairing_$uuid`)。
- **UI 框架与风格选型**：选择 Jetpack Compose + Material 2 (M2) 风格。禁用 M3 超大圆角和淡粉色调色盘。同步宿主 App 的 `colorPrimary` 主题色。
- **存储与响应式状态**：`EncryptedSharedPreferences` 存储，对外暴露 `StateFlow<List<PairedDevice>>` 实时响应设备变动。
- **重复扫码拦截**：扫码已绑定过的 PC `uuid` 时直接弹 Toast 提示“该设备已绑定”。
