<div align="center">

# 微话助手

微信里的聊天分析、回复建议与话题助手。

![Android](https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white)
![Platform](https://img.shields.io/badge/LSPosed%20%2F%20Xposed-Module-555555)

</div>

微话助手在微信聊天中提供对话分析、复盘、回复建议和话题推荐。它不会自动发送消息，回复需要你确认后手动发送。

## 环境要求

- Android 9+，已安装可用的 LSPosed / Xposed
- 使用内置 WorkBuddy 登录作为模型服务

## 使用

1. 安装 APK 后先从桌面打开一次「微话助手」。
2. 在 LSPosed 中启用模块，作用域勾选微信，然后彻底结束微信进程再重新打开。
3. 长按微信消息可分析；展开「＋」进入「帮我回 · 找话题」。
4. 在设置页配置模型服务、角色背景、可选回复条数与上下文回溯条数。

## 构建

```powershell
powershell -File tools/bump-version.ps1
tools\gradle.ps1 :app:assembleRelease --no-daemon "-Pkotlin.compiler.execution.strategy=in-process"
```

`version.properties` 是唯一的版本来源，构建产物位于 `app/build/outputs/apk/release/`。
