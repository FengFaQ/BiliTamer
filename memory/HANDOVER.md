# 交接手册 / Handover

> 给「下一个接手的人或会话」：先读 `PROJECT-STATE.md`，再按本文件动手。
> 本仓库是 **BiliTamer 的独立维护线**（FengFaQ），上游历史保留但不再自动同步。

## 1. 这个仓库是什么

一个 **LSPosed 模块**（包名 `com.tamer.bili`），作用于国际版哔哩哔哩
`com.bilibili.app.in`。它通过 hook 宿主的方法/返回值来加功能，**不修改宿主 APK 本体**。
当前版本 v1.10.2，核心新增是「默认倍速」与「更多倍速档位」。

仓库同时保留上游的全部功能（IP 属地、解码/音质/HDR 顺位、首页清洗、下载加速等），
上游功能的细节以 `PITFALLS.md` 与源码注释为准。

## 2. 构建（唯一支持的路径）

**不要用 Gradle / Android SDK**——本仓库自带构建引擎，走 javac → dx → 手写 AXML → zip → 签名。

```bash
# 在 WSL 里（Ubuntu 24.04，JDK17 已装）
cd <repo>
# 1) 一次性：准备签名（仓库不含任何密钥，tools/signing.local 被 gitignore）
keytool -genkeypair -v -keystore ~/my.jks -alias mymod -keyalg RSA -keysize 2048 -validity 10000
printf 'KS_PATH=%s\nKS_PASS=%s\nKS_ALIAS=%s\n' ~/my.jks '<pass>' mymod > tools/signing.local
# 2) 构建（约 30 秒）
python3 tools/build_module.py
# 产物：dist/BiliTamer-v<version>.apk
```

* 版本号在 `module_conf.py`（`version_name` / `version_code`，两者要一起加）。
* 构建前先跑单测：
  `python3 tools/speedpolicy_test.py && python3 tools/speedlistpolicy_test.py`
* 构建会把 `build/`、`dist/` 写出来（均 gitignore）；**提交的产物只放 `apk/BiliTamer-v<ver>.apk`**
  （CI 发版就取这个文件）。

## 3. 发版流程（CI 自动建 Release）

`.github/workflows/release.yml`：**推送 `v*` tag** → 从 `RELEASE_NOTES.md` 里截取
`## v<ver>` 小节当 Release 说明 → 附上 `apk/BiliTamer-v<ver>.apk`。

```bash
# 1) 改 module_conf.py 的版本；2) 在 RELEASE_NOTES.md 顶部加 ## vX.Y.Z 小节
# 3) 构建并提交产物
python3 tools/build_module.py
cp dist/BiliTamer-vX.Y.Z.apk apk/
git add -A && git commit -m "BiliTamer vX.Y.Z - ..."
# 4) 打 tag 并推送（只有 tag 会触发发版）
git tag vX.Y.Z
git push origin main
git push origin vX.Y.Z
```

发版失败的常见原因：`apk/BiliTamer-v<ver>.apk` 不存在（workflow 第一步就 `test -f`）、
`RELEASE_NOTES.md` 里没有对应小节、仓库 Actions 权限被限制。

## 4. 代码地图（改功能从这里进）

```
app/src/main/java/com/tamer/bili/
├── MainHook.java          # libxposed 入口：进程判定、配置装配、各 hook 组 install()
├── BiliConfig.java        # conf 键、默认值、严格解析（只认 boolean/int/单行字符串）
├── hooks/                 # 各功能 hook 组
│   ├── SpeedHooks.java    # 默认倍速（只改「起播」那一次 setPlaySpeed 入参）
│   ├── SpeedPolicy.java   # 默认倍速纯逻辑（clamp/百分比换算）
│   ├── SpeedMenuHooks.java# 自定义档位表（后处理宿主档位列表的返回值）
│   ├── SpeedListPolicy.java   # 档位表纯逻辑（解析/去重/夹取/合并）
│   └── ...                # 其它功能组
└── ui/
    ├── SettingsActivity.java  # 纯代码设置页（无资源 XML）
    └── ConfSync.java          # SP → conf 文本 → 投递到宿主进程
```

**加一个开关的固定动作**：`BiliConfig` 加键 + `ALL_KEYS` + 解析分支（int/字符串/key 类型别搞错）
→ `HookApi` 加读取方法 → `MainHook` 实现（每次现读，不要缓存）→
`ConfSync.collectKv` 加写侧分支 → `SettingsActivity` 加控件 → hook 里读。

## 5. 验证口径（没有设备时怎么说话）

本机经常**没有真机**（`adb devices` 为空）。这种情况下只允许说三件事：

1. **dex 静态实证**：锚点/类/方法/指令在目标版本的 APK 里确实存在（给类名 + dex 文件名）；
2. **构建与签名**：`python3 tools/build_module.py` exit 0、`jarsigner -verify` exit 0、
   包名/版本/入口/scope 元数据正确；
3. **纯逻辑单测**：`tools/*_test.py` 全绿（需要先能红，见 `ttd`/变异测试）。

**上机确认方法**（有设备时）：装 APK → LSPosed 启用并勾选 B 站国际版 → 强停重开，
设置页最下方「导出日志到文件」，看这些行：

| 行 | 含义 |
| --- | --- |
| `speed: anchor L1 hit hint eK1.q#setPlaySpeed` | 默认倍速找到落点 |
| `speed: armed on …#setPlaySpeed(float) (stage=L2)` | 钩子挂上 |
| `speed: applied 1.50 at start (was 1.00)` | 真的在起播时改了速度 |
| `speed: pass through non-startup call … from <类>#<方法>` | 非起播放行（用户手动选档不该被拦） |
| `speedmenu: armed on …/utils/B#a (stage=M1)` | 档位表钩子挂上 |
| `speedmenu: applied N entries [...] (host offered M)` | 档位表真的被替换 |

只有 `armed` 没有 `applied` ⇒ 起播没走到 `setPlaySpeed`（宿主换了路径）或配置就是 1.0x；
`anchor miss` ⇒ 混淆名变了，按 `docs/speed-menu-anchor-6.7.1.md` §7.3 重新校锚。

## 6. 禁区 / 纪律

* **不要把密钥、PAT、口令、cookie、播放地址写进任何提交**；凭据只在本机 `~/.ssh/`。
* **改宿主静态 `final` 字段要极其谨慎**：各版本 ART 行为不一致，而且会绕过宿主自己的 gate
  （`docs/speed-menu-anchor-6.7.1.md` §4.3 有一份「为什么不动静态字段」的清单）。
* **改写型 hook 必须能「安静失败」**：找不到锚点就打 warn 并保持零干预，不许猜着改
  （`PITFALLS.md` §40/§63 的教训）。
* **文档与代码同批改**：改了行为就同步 `README` 功能表、`RELEASE_NOTES` 与相关 `PITFALLS`，
  否则下一次交接会被过期文档带偏（v1.10.1 的 `SpeedSession` 就是这么被审计发现的）。
* 单测的 `.class` 只能落在 `/tmp`：`build/` 会被整个 dex 进 APK（`PITFALLS.md` §52）。
