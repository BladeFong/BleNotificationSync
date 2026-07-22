# 阶段规划、决策记录 （拆分自 task_plan.md）

## 定位与功能描述
多 PC 绑定与内置设备管理组件模块。支持 Android SDK 关联多台 PC 设备，提供以 UUID 为唯一标识的持久化存储，以及 Jetpack Compose Material 2 风格的设备管理 Fragment/Activity。

---

# 研究发现、技术决策、需求分析 （拆分自 findings.md）

## 技术决策与踩坑记录
- **放弃随机 MAC 做主键**：BLE GATT 获得的 MAC 地址由于 privacy 机制存在随机化（RPA），频繁改变，不可作为设备持久化主键。改成 PC 二维码中的固定 `uuid` 作为 Key (`pairing_$uuid`)。
- **二维码协议兼容**：PC 端在最新版本中去除了随机改变的 MAC 地址，仅保留 `uuid` 和 `name`（格式 `ble://pair?uuid=${uuid}&name=${name}`）。SDK 端的 `QrDecoder` 调整为只强校验 `uuid` 参数，`mac` 改为可选解析。
- **设备唯一标识对齐**：针对多 Android 设备相同 App 绑定同一 PC 的场景，在 REGISTER 帧的 JSON 荷载中带上 `android_id` 和 `device_name`。PC 端将以 `android_id` 作为区分不同手机设备的唯一标识。

- **宿主 Activity 主题继承**：SDK 内置 Activity (`DeviceManagerActivity`) 不在 Manifest 中硬编码 `android:theme`，以便其启动时 100% 继承宿主 `<application android:theme="...">` 中定义的主题。

- **Compose 动态解包宿主 XML 属性**：针对 `@color/...` 引用资源，`TypedValue.data` 返回的是 Resource ID，不能直接转 Color。采用 `theme.obtainStyledAttributes(intArrayOf(attrId)).getColor(0, 0)` 解包底层的真实 ColorInt，完全兼容 AppCompat / MD3 主题。
- **WindowInsets 避让**：给 Compose TopAppBar 和 BottomBar 加上 `statusBarsPadding()` 和 `navigationBarsPadding()`，适配沉浸式状态栏与手势导航条。
- **字符串资源国际化**：SDK 内置字符串提取至 `strings.xml`，默认路径 `values/strings.xml` 为英文，`values-zh/strings.xml` 为中文，资源名称统一加 `s_` 前缀。

