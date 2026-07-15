# Demo App — 进度日志

## 2026-07-15 — 设计与实现

- **Status:** complete ✅
- 设计文档：`demo/DESIGN.md`
- 实现计划：`demo/PLAN.md`（7 Tasks）
- 图标：矢量勾选方框（ic_todo.xml）+ adaptive icon

### Task 1: Settings + Build 配置
- **Status:** ✅ done

### Task 2: AndroidManifest.xml
- **Status:** ✅ done

### Task 3: 图标 VectorDrawable + Adaptive Icon
- **Status:** ✅ done

### Task 4: 字符串资源
- **Status:** ✅ done

### Task 5: Layout activity_main.xml
- **Status:** ✅ done

### Task 6: MainActivity.kt
- **Status:** ✅ done

### Task 7: 全量编译验证
- **Status:** ✅ done
- 编译：BUILD SUCCESSFUL (:sdk + :demo)

### 额外补充
- SDK 补全：ReminderScheduler + AlarmReceiver + setReminder/cancelReminder + ReminderCallback（之前被 revert 丢失）
- SDK Manifest 补全：BLE 权限 + POST_NOTIFICATIONS + AlarmReceiver 注册
