# 项目状态快照 / Project state

> 最后更新：2026-11-08（v1.10.2 交付时）
> 维护者：FengFaQ（独立维护线，非上游官方）

## 一页纸

| 项 | 值 |
| --- | --- |
| 仓库 | <https://github.com/FengFaQ/BiliTamer>（public，独立仓库，不再向上游同步） |
| 分支 | `main`（本仓唯一开发分支；上游历史完整保留，见 `git log`） |
| 上游基线 | `mengwuzhuanshou/BiliTamer` `main` @ `46505f7`（v1.9.9） |
| 当前版本 | **v1.10.2** / versionCode **40** / 包名 `com.tamer.bili` |
| 提交 | `0e058f5`（v1.10.2 功能 + `memory/` 记忆目录）；功能提交 `125a8ce` |
| Tag | `v1.10.2`（annotated，指向 `0e058f5`） |
| Release | <https://github.com/FengFaQ/BiliTamer/releases/tag/v1.10.2>（附件 `BiliTamer-v1.10.2.apk`，180,918 B） |
| 产物 | `apk/BiliTamer-v1.10.2.apk`（与 Release 附件同源；sha256 `5d5f3aee…9621fa`） |
| 目标宿主 | 哔哩哔哩国际版 `com.bilibili.app.in` **6.7.1**（versionCode 9161100）校准；6.3.0–6.6.0 保留既有适配，但倍速两项只在 6.7.1 校准过 |
| 构建方式 | WSL(Ubuntu 24.04) + JDK17 + `python3 tools/build_module.py`（无 Gradle/Android SDK） |
| 单测 | `tools/speedpolicy_test.py`（39 格）、`tools/speedlistpolicy_test.py`（34 格）全绿 |

## 本版新增（v1.10.2）

1. **默认倍速管理**：每次起播套用用户设定倍速（0.25x–3.0x）；用户手动选档**不被拦**
   （立即生效），下一次起播回到默认值。
2. **更多倍速档位**：设置页可填自定义档位表，**替换**播放器倍速菜单；留空 = 不接管。
   去重、降序、夹到 0.25–3.0、最多 12 档，默认倍速自动补入表内。

两者都是「零干预优先」设计：配置为空/开关关闭时，宿主行为一个字节都不改。

## 关键已知问题 / 风险

| 级别 | 项 | 说明 |
| --- | --- | --- |
| 中 | **未上真机 A/B** | 本版只做了 dex 静态实证 + 构建 + 纯逻辑单测。菜单是否真的出现档位、起播是否真的 `applied`，需要在有设备时确认（方法见 `HANDOVER.md` §5） |
| 中 | `onPrepared` 帧依赖 | 起播判据是调用栈里有 `onPrepared`。若某版宿主改路径 → 静默不套用（已加 `30 次非起播调用且从未 applied` 的 warn 兜底） |
| 低 | 3.0x gate 被绕过 | 自定义表里的 3.0 会绕过宿主的实验组/下载态 gate；3.0 本身在 native 上限内，离线下载场景观感未验 |
| 低 | 2.0 档选中态 | 宿主对 `1.99` 的反向归一用 `|1.99-2.0| < 0.01`（严格小于），2.0 档高亮可能不稳；这是宿主既有行为 |
| 低 | 旧版本宿主 | 倍速两项在 6.3.0–6.6.0 大概率静默不生效，日志有 `anchor miss` 明确提示 |

详见 [`PITFALLS.md`](../PITFALLS.md) §60/§63、[`docs/speed-default-audit.md`](../docs/speed-default-audit.md)、
[`docs/speed-menu-anchor-6.7.1.md`](../docs/speed-menu-anchor-6.7.1.md)。

## 文档地图

| 文档 | 内容 |
| --- | --- |
| [README.md](../README.md) | 功能表、安装、构建、排查（面向用户） |
| [PITFALLS.md](../PITFALLS.md) | 实现笔记与坑（**最重要**的工程记忆，60+ 条） |
| [RELEASE_NOTES.md](../RELEASE_NOTES.md) | 按版本的发版说明 |
| [docs/speed-menu-anchor-6.7.1.md](../docs/speed-menu-anchor-6.7.1.md) | 倍速菜单锚点 dex 实证（含复算命令、调用点、抗漂移） |
| [docs/speed-default-audit.md](../docs/speed-default-audit.md) | 默认倍速独立审计 + v1.10.2 复核 |
| [docs/speed-anchor-6.7.1.md](../docs/speed-anchor-6.7.1.md) | 默认倍速锚点实证（v1.10.1 原始记录，含 v1.10.2 修订说明） |
