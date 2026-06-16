# Phase 4 端到端验证清单

> 版本: 0.2.0 | 日期: 2026-06-15

---

## 1. 构建验证

- [x] `./gradlew :core:test` — 全部 30 个测试通过
- [x] `./gradlew :app:assembleDebug` — APK 构建成功，AIDL 编译通过
- [ ] `adb install app/build/outputs/apk/debug/app-debug.apk` — 安装到设备/模拟器

## 2. 服务端验证

```bash
conda activate v2t
uvicorn server.app:app --reload --host 0.0.0.0 --port 8080
```

- [x] `curl http://localhost:8080/health` → `{"status":"ok","version":"0.1.0"}`
- [x] `curl -X POST http://localhost:8080/v1/transcribe -H "Content-Type: application/json" -d '{"audio":"AAECAw==","style":"正式"}'` → 返回正常转录结果
- [x] `curl -X POST http://localhost:8080/v1/feedback -H "Content-Type: application/json" -d '{"original_text":"a","styled_text":"b","final_text":"c","style":"正式","duration_ms":100}'` → `{"status":"ok","event_id":"..."}`
- [x] `curl http://localhost:8080/v1/feedback/count` → `{"count": N}`
- [x] `cat server/data/feedback.jsonl` — 包含已写入的记录

## 3. 独立 App 端到端测试

### 前提条件
- 模拟器或真机上安装 APK
- `adb reverse tcp:8080 tcp:8080`（真机 USB 连接时）
- 服务端正在运行

### 测试步骤

1. [ ] 启动 App，选择风格（正式/精简/礼貌/翻译英文）
2. [ ] 按住"按住说话"按钮 → 状态显示"录音中"
3. [ ] 松手 → 状态显示"处理中"
4. [ ] 等待 → 状态显示"已完成"，结果文字显示在结果区
5. [ ] 点击"复制"按钮 → Toast "已复制到剪贴板"
6. [ ] 结果区下方出现"反馈测试"区域，EditText 预填风格化文本
7. [ ] 在 EditText 中修改文本（改动超过 10%）
8. [ ] 点击"发送反馈" → Toast "反馈已发送 (event_id=...)"
9. [ ] 在服务端检查 `server/data/feedback.jsonl` 包含新记录
10. [ ] 修改文本与原文相同 → 点击"发送反馈" → Toast "无显著差异，不发送反馈"

## 4. Fcitx5 主应用集成验证（参考）

当 Fcitx5 主应用完成集成后，验证以下项目：

- [ ] 键盘上出现语音按钮
- [ ] 按住语音按钮 → 插件开始录音（logcat 可见 `startRecording()`）
- [ ] 释放按钮 → 文本成功上屏到输入框
- [ ] 语音按钮状态随录音/处理/空闲变化
- [ ] 编辑输入框内容后再次点击语音按钮 → `VoiceFeedbackCollector.checkAndCollect()` 触发
- [ ] 反馈事件出现在服务端 `feedback.jsonl` 中
- [ ] 插件进程独立运行（`ps | grep fcitx_plugin_voice`）
- [ ] 重启输入法后风格设置保持不变

## 5. 回归检查

- [x] 所有旧有测试仍通过（Phase 1-3 的功能未受损）
- [x] AIDL 向后兼容：新增参数类型为 String，旧客户端传 null 亦可工作
- [x] TranscribeUseCase sessionId 参数可选，不影响无 sessionId 的调用方

## 6. 已知限制

- `MainActivity.kt` 使用已废弃的 `launchWhenStarted`（已在技术债务中记录）
- `FeedbackRepository` 参考实现使用内存队列，生产环境应替换为 Room/SQLite
- JSONL 存储设 10000 行上限，后续可迁移到数据库
- 参考实现中 `VoiceFeedbackCollector` 的 `VoiceFeedbackEvent` 是独立定义，主应用应直接使用 core 模块的版本
