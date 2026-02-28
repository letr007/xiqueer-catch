# xiqueer-catch

用于抓取喜鹊儿课表数据并导出 CSV（可导入 WakeUp 课程表）的 Android 工具。

语言：简体中文 | [English](README.md)

## 功能

- 通过本地 VPN 拦截抓取课表接口响应
- 按学年学期对抓取快照分组
- 按周选择快照并合并为导出行
- 导出 CSV 到下载目录
- 内置使用教程与日志查看

## 环境要求

- Android Studio Iguana+（或更新稳定版）
- JDK 11
- Android SDK 34
- minSdk 24，targetSdk 34

## 构建

```bash
./gradlew assembleDebug
./gradlew test
```

Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
```

## 使用说明

1. 先打开喜鹊儿，再打开本应用。
2. 点击 `Start Capture` 并授予 VPN 权限。
3. 回到喜鹊儿，清除缓存并进入课表页面。
4. 切换到目标学期，从第 1 周开始逐周刷新。
5. 回到本应用，勾选要导出的快照。
6. 点击 `Export CSV`，将结果导入 WakeUp 课程表。

