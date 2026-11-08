# 默认倍速 · 独立审计报告

> 审计对象：BiliTamer v1.10.1 工作区（base commit `46505f7` = v1.9.9 + 未提交改动）
> 宿主：哔哩哔哩国际版 6.7.1，versionCode 9161100，`/root/work/apk/xapk/com.bilibili.app.in.apk`（34 个 dex）
> 方法：androguard 4.1.4（venv `/root/work/venv/bin/python`）做 dex 静态实证；JDK 17 反编译 libxposed
> API jar 核对签名；`python3 tools/build_module.py` 做构建验证；两套纯 JVM 单测独立重跑 + 变异实验。
> 角色：独立审计（未参与实现）。**没有真机**（`adb devices` 为空），凡是只能上机确认的点都在 §7 单列。

## 结论摘要

| 档位 | 条数 | 一句话 |
| --- | --- | --- |
| **BLOCKER** | **0** | 锚点（`setPlaySpeed` / `R0` / `onPrepared` 链）在 6.7.1 dex 上逐条成立；构建、签名、入口元数据全部通过 |
| **BUG** | **3** | ① 手动调速识别类名在 6.7.1 的真实链路上不会出现在调用栈里 → 用户手选倍速会被静默改回配置值（高）；② 多版本适配声明与「只锚 6.7.1」不符（低）；③ 手动值跨线程读写无同步（潜在，低；被 ① 掩盖） |
| **NIT** | **8** | 主要是 PITFALLS §60 的验收口径/竞态解释过期或与实现不符、SpeedHooks 类注释与实现不一致、`follows()` 死代码被单测覆盖、单测把宿主类名写死在测试侧等 |

**核心判断**：主落点选得对。`eK1.f#onPrepared` 在 6.7.1 里**无条件**先 `q.R0(false)`、紧接着
`q.setPlaySpeed(v)`（classes28.dex 字节码 `eK1/f.onPrepared` 第 9/11 条指令），而 `setPlaySpeed`
的入参同时喂 pref、`Ops.OpSwitchSpeed`、日志三处——替换入参确实能让「上层 + 内核 + pref」一致。
「每次起播套用默认倍速」这条主功能**按 dex 证据应当生效**（仍以真机 §7 为准）。

**必须先修的是「手动跟随」**：它在本版的两条真实手动链路上都识别不到（见 BUG-1），
而代码对「非起播、非手动」的调用一律改回配置值，所以开开关后用户在播放器里手动选的倍速会
**当场被改回去**——这与 README / 发行说明 / 设置页文案承诺的行为相反。

---

## 0. 审计快照与并发变更（重要）

本次审计读代码的时间窗口是 **2026-11-08 08:15–08:30**。期间工作区**被另一个会话并行修改**
（共享文件系统）：08:27–08:33 出现了新的「自定义倍速档位表」功能
（`hooks/SpeedMenuHooks.java`、`hooks/SpeedListPolicy.java`、`tools/speedlistpolicy_test.py`，
以及 `BiliConfig.KEY_SPEED_LIST` / `HookApi.getPlaybackSpeedList()` / 设置页新输入框），
`module_conf.py` 已 bump 到 **1.10.2（versionCode 40）**，`dist/` 在 08:29 / 08:33 被那个会话重新构建
（当前 `dist/BiliTamer-v1.10.2.apk`、`dist/BiliTamer-v1.10.1.apk` 都不是本次审计构建出的那两个文件）。

因此本报告的边界是：

- **默认倍速的三个核心文件在「审计快照」（08:15–08:22）里是**：`SpeedHooks.java` `dadee95d…`、
  `SpeedPolicy.java` `a58bf025…`、`SpeedSession.java` `93177e8d…`（mtime 10-31）。
  **08:34 起它们被另一个会话继续重写**（SpeedHooks/SpeedPolicy 改动、`SpeedSession.java` 删除、
  `speedpolicy_test.py` 从 61 格变成 39 格），这次重写看起来正是按本报告 BUG-1 的方向修
  「非起播一律放行」——见 §9。本报告 §1/§2 描述的是审计快照，不是当前树。
- `MainHook.java` / `BiliConfig.java` / `ConfSync.java` / `SettingsActivity.java` / `HookApi.java`
  已被并行修改（新增档位表功能；默认倍速的代码段保留、行号整体后移 1–9 行）。
  本报告的引用已按 **08:33 复核后的行号** 更新，默认倍速相关行为已逐条重新核对。
- §5 的构建验证只覆盖 **08:22 的快照**（当时树里还没有档位表代码，产物 176,822 字节、
  sha256 `2981bc9d…`，随后被并行会话在 08:29 覆盖）。**发版前必须在树稳定后重新构建/验签一次。**
- 本次审计**没有**审计「自定义倍速档位表」（`SpeedMenuHooks`/`SpeedListPolicy`）：它在审计窗口内
  还在写，且与默认倍速是两条独立链路。但它**不修复 BUG-1**：菜单点选后仍然走
  `setPlaySpeed` 的同一条调用链，栈里仍然没有 `PlaySpeedManagerImpl`。

审计开始前的原始产物备份仍在 `/root/work/backup/`：
v1.10.0 `fba34b76…`、v1.10.1 `fc948554…`（后者是 10-31 的原始构建，不是当前 dist 里的同名文件）。

---

## 1. 锚点实证（对应任务 1a–1e）

所有结论都用 `androguard.core.dex.DEX` 的 class_defs / code_item 直接读出，不是字符串 grep。

### 1a. `public void setPlaySpeed(float)` 的声明类 —— ✅ 成立

`/root/work/apk/base/classes28.dex` 里共有 **5 个类**声明了 `(F)V` 形状的 `setPlaySpeed`，
其中**具体实现**（非 abstract）的只有两个播放器，目标类就是文档写的那个：

| 类 | 访问标志 | dex |
| --- | --- | --- |
| **`LeK1/q;` ← 目标** | `public final` | classes28.dex |
| `Ltv/danmaku/video/bilicardplayer/player/d;` | `public final`（卡片播放器，另一个播放器） | classes28.dex |
| `Ltv/danmaku/biliplayerv2/service/A;` | `public abstract`（接口声明） | classes28.dex |
| `LoL1/o;` | `public abstract`（内联播放器接口） | classes28.dex |
| `Ltv/danmaku/video/bilicardplayer/player/a$a$b;` | `public final`（卡片播放器适配） | classes28.dex |

`LeK1/q;` 的完整形状与文档一致：

```
class LeK1/q;  access=public final  super=LcK1/a;  interfaces=[Ltv/danmaku/biliplayerv2/service/A;]
  public final setPlaySpeed (F)V        ← 文档中的 eK1.q#setPlaySpeed
  public final R0 (Z)F                  ← 文档中的 eK1.q#R0
```

补充证据（说明它确实是宿主主播放器链路的一环，不是同名巧合）：

- `LeK1/q;` 是 classes28.dex 里**唯一**实现 `Ltv/danmaku/biliplayerv2/service/A;` 的类；
- `LeK1/q;` 的 `<init>` 里 `new-instance LeK1/f;` 并把 `this` 传给 `eK1/f.<init>(eK1/q;)V`，
  `LeK1/f;` 的字段就是 `b LeK1/q;`、接口是 `IMediaPlayer$OnPreparedListener`；
- `LeK1/q;` 的源码文件索引（`source_file_idx`）与 `LeK1/f;`、`Ltv/danmaku/biliplayerv2/service/A;`
  相同（3008），三者是同一份 Kotlin 源文件编译出来的。

**注意**（对「形状兜底」的补充）：这 5 个类里有两个 `(F)V` 的 abstract 声明和两个卡片播放器实现。
如果将来 `eK1.q` 改名而代码仍只认 `CANDIDATE_HOLDERS`，是找不到的（见 NIT-2）；
这不是 6.7.1 的问题，但说明「按形状自动定位」在这版并未实现。

### 1b. 同名类上的 `R0(boolean)float` —— ✅ 成立（pref 字符串 + 1.0f 常量都在）

`LeK1/q;` 上 **`(Z)F` 实例方法恰好 1 个**：`public final R0 (Z)F`。字节码（classes28.dex）：

```
R0(Z)F:
  const/high16  v0, 1065353216        # = 0x3F800000 = 1.0f   ← 默认返回值
  if-eqz        v3, +02ch             # p1 == false 时跳去读 pref
  ... iget-object v3, LH LHL1/r;-> b IMediaPlayAdapter;
      invoke-interface ... IjkMediaPlayer;->getSpeed(F)F      # true 分支：读内核当前速率（默认参数 1.0f）
  ...
  const-string  v1, "player_key_video_speed"                  ← pref 键名
  invoke-interface v3, v1, v0, ...setting/c;->getFloat(Ljava/lang/String;F)F   ← 默认参数 v0 = 1.0f
  return v0
```

即：`R0(false)`（起播时用的分支）读 pref `player_key_video_speed`、缺省 1.0f；
`R0(true)` 走 `IjkMediaPlayer.getSpeed(1.0f)`。文档 §1b 的说法逐字成立。

### 1c. `onPrepared` 是否真的调到 `setPlaySpeed` —— ✅ 成立（无条件调用，不是分支）

`LeK1/f;` 实现 `IMediaPlayer$OnPreparedListener`，其 `onPrepared(IMediaPlayer;)V` 的字节码（classes28.dex）：

```
 0 const-string  "PlayerCoreServiceV2"
 1 const-string  "[ijk][callback]player onPrepared"
 3 iget-object   v1, v6, LeK1/f;->b LeK1/q;
 4 iget-object   v2, v1, LeK1/q;->b LcK1/e;
 6 if-eqz        v2, +076h               # 只是 mPlayerContainer 空判
 8 iput-boolean  v4, v2, LcK1/e;->y Z
 9 invoke-virtual v1, v4, LeK1/q;->R0(Z)F          ← R0(false)
11 invoke-virtual v1, v2, LeK1/q;->setPlaySpeed(F)V ← 紧接着用 R0 的返回值设速
```

这是「起播必走」的最强证据：R0 与 setPlaySpeed **相邻、无分支**，只在
`mPlayerContainer != null` 的保护下。文档链 `eK1.f#onPrepared → q.R0(false) → q.setPlaySpeed(v)` 成立。

另外把文档里「为什么 v1.10.0 的锚点会死」的三条也复核了：

- `IjkMediaPlayer`（classes28.dex，266 个方法）里 **没有 `resume`**；`setSpeed(F)V`、`start()V`、
  `pause()V`、`getSpeed(F)F` 都在 → 文档「本版没有 resume()」成立；
- `setPlaySpeed` 方法体里依次：`setting/c.M7(F,"player_key_video_speed")` 写 pref →
  `Ops.OpSwitchSpeed` 经 `LHL1/r;->e(Ops,Object)` 下发 → 末尾日志 `"[player] player speed type="`；
  `HL1.r.e` 再 `invoke-interface IMediaPlayAdapter;->e(Ops,Object)`，`LJL1/d;->e(Ops,Object)` 里
  确有 `invoke-virtual IjkMediaPlayer;->setSpeed(F)V`。文档的适配器链成立。
- 「2.0 特殊降成 1.99」也复现了：方法体里 `const/high16 v1, 1073741824`（=2.0f）→
  `cmpg-float` → `if-nez` 后 `const v0, 1073657938`（=1.99f）。文档 §60 的这条附加说明成立。

### 1d. 「形状兜底」在 6.7.1 上会不会误命中 —— ✅ 不会（该类只有一个 `(Z)F`）

`LeK1/q;` 的 `getDeclaredMethods()` 里 `(Z)F` 只有 `R0` 一个（上面 1b 已实测计数 = 1）。
所以 `SpeedHooks.installInitialSpeedHook()` 的「唯一 `(Z)F`」兜底在 6.7.1 上**不会误命中**。
（但代码并没有实现注释里说的「方法体里出现 pref 字符串 / 1.0f 常量」这条判据，见 NIT-2。）

### 1e. `eK1.q` 能不能被宿主 ClassLoader 加载 —— 静态上可确认拼写与 dex，运行期需真机

- `CANDIDATE_HOLDERS = {"eK1.q"}`（`SpeedHooks.java:97-99`），`HookUtil.cls()` 用
  `Class.forName(name, false, classLoader)`（`HookUtil.java:24-29`）；
- dex 里类名是 `LeK1/q;`，与 `eK1.q` 拼写一致（无大小写/分隔符错误）；
- `classes28.dex` 在 **base APK**（`com.bilibili.app.in.apk`）里，和 `classes.dex`…`classes34.dex`
  一起由应用自身的 PathClassLoader 加载（base dex 集合，不是 split/插件 dex）——
  这是「libxposed 给的 ClassLoader 应该能 loadClass」的静态依据。

但这仍是**推断**：只能静态确认类名/所在 dex，无法证明运行期 `param.getClassLoader()` 就是加载
`classes28.dex` 的那个 loader（若宿主对播放器代码用了独立 ClassLoader，静态上无法排除）。见 §7。

### 1f.（审计中额外发现）Theseus 包装层：手动调速的调用栈里到底有谁

6.7.1 里 `Ltv/danmaku/biliplayerv2/service/A;` 有两个实现：
`LeK1/q;`（classes28，旧播放器核心）和一个 Theseus 适配器
`…/theseus/united/player/oldway/playercontainer/TheseusPlayerContainerProvider$…$1$1$1;`（classes22）。
适配器的 `setPlaySpeed(F)V` 只做一件事：`h.t(F)`（`h` = `com.bilibili.ship.theseus.keel.player.h`）。
`h.t(F)` 又是直接 `iget` `PlaySpeedManagerImpl.a` 的 `StateFlowImpl` 并 `setValue`，再经
flow 收集器 `com.bilibili.player.tangram.playercore.k.emit(...)` → `PCSFacadeImpl.b`（旧核心 `A`）
→ 最终仍落到 `eK1.q#setPlaySpeed`。

结论：**无论旧 UI 还是 Theseus UI，真正改速度的最后一步都是 `eK1.q.setPlaySpeed`**——
这反而加强了对主落点的信心；但它同时是 BUG-1 的证据（见下）。

---

## 2. 代码审计

### 2.0 逐项核对：这些点查过，结论是「一致/正确」

- **conf 键读写一致**：写侧 `ConfSync.collectKv()` 把 `playback_speed_pct` 归入 int 分支
  （`ConfSync.java:63-64`，`getInt(key, defaultIntOf)`），`kvConfText()` 按键值对逐行输出，自然带上该键
  （`ConfSync.java:76-85`）；读侧 `BiliConfig.parseConfText()` 的 int 分支包含 `KEY_SPEED_PCT`
  （`BiliConfig.java:435-439`），布尔键走严格 `true/false` 分支（`:442-443`），两键都在 `ALL_KEYS`
  （`BiliConfig.java:195-197`），`toNormalizedText()` 也按 `ALL_KEYS` 重建（`BiliConfig.java:476-487`）。
  **写进去的键，宿主一定解析得到**；不存在「设置页存了、解析侧当未知键丢掉」的坑。
  （并行新增的 `playback_speed_list` 同样已接入读/写两侧：`BiliConfig.java:440`、
  `ConfSync.java:68`，同一套口径。）
- **默认值与越界**：开关默认 `false`（`BiliConfig.java:230`），倍速默认 `150`（`:249`）；
  写入侧校验 25..300（`SettingsActivity.java:454-466` 用 `SpeedPolicy.MIN/MAX` 0.25..3.0，`toPct` 再夹一次，
  `SpeedPolicy.java:151-158`）；读取侧 25..400 兜底（`BiliConfig.java:129-146` 注释、`SpeedPolicy.java:167-175`），
  行为边界恒为 0.25..3.0。三层口径与文档一致。
- **日志分级**：`armed` / `applied` / `manual` / `clamp` 用 `info`（不受 verbose 影响，`MainHook.info` →
  `writeLog(INFO)`），`L1 miss` / conf 越界用 `warn`（`SpeedHooks.java:151,196`；`MainHook.java:948`），
  与文档「每类首条必出、失败要可见」一致。
- **设置页落盘**：倍速输入用 `commit()` 后再 `persistAll(false)`（`SettingsActivity.java:473,478`），
  顺序正确（先落盘、后汇总/投递）。
- **装配位置/隔离**：见 §6，只在主进程、受 master 开关保护、单个 hook 组失败不影响其它组。
- **libxposed API**：见 §5 末尾，签名核对通过、编译通过。
- **重入**：hook 只替换入参，不回调宿主；监听器 `U.a(F)` 的两个实现在 classes28.dex 里只做埋点，
  不回到 `setPlaySpeed`（不会 ping-pong）。

下面三条是**真正找到的问题**。

### BUG-1（高）手动调速识别在 6.7.1 上必然失败 → 用户手选倍速被静默改回配置值

**结论**：`SpeedPolicy.isManualChange()` 用 `MANUAL_MARKER.equals(f.getClassName())` 精确等值匹配
`com.bilibili.player.tangram.basic.PlaySpeedManagerImpl`（`SpeedPolicy.java:36,112-123`），
但 6.7.1 的两条真实手动链路上，这个类**不会作为栈帧出现**。于是「手动」永远判 false，
调用落进 `SpeedHooks.decide()` 的最后一支 `session.onHostReset(configured)`（`SpeedHooks.java:299-304`），
把用户的请求值改回配置值。

**证据 1（该类根本没有调用播放器设速的代码）**：classes18.dex 里
`Lcom/bilibili/player/tangram/basic/PlaySpeedManagerImpl;` 只有三个方法：
`<init>()V`、`a(F,Continuation)Ljava/lang/Object;`、`k()Lkotlinx/coroutines/flow/d;`。
逐条读字节码：`<init>` 只建两个 `StateFlowImpl` + 一个锁；`a()` 只更新 StateFlow 并 `yield`；
`k()` 只组装 flow。**没有任何一条指令引用 `A.setPlaySpeed` 或旧核心**。
它唯一一次出现在「设速链」上，是别人从它的 StateFlow 里取到值再去设速。

**证据 2（旧 UI 面板：直连，栈里是 widget 类）**：classes18.dex
`Lcom/bilibili/playerbizcommonv2/widget/speed/e;`（`SpeedFunctionWidget`，旧的播放器倍速面板）：

```
e$a.b(F)V                                  ← 适配器回调（用户点某一档）
  invoke-static e;->f(Le; F)V              ← lambda
e.f(Le; F)V
  invoke-virtual ...->o(LcL1/b;)V          ← 埋点
  invoke-interface ... biliplayerv2/service/A;->setPlaySpeed(F)V   ← 直接设速
  const-string "[player]playbackrate list widget hide;[player]current playbackrate="
```

栈帧类名是 `com.bilibili.playerbizcommonv2.widget.speed.e`（或 `…speed.e$a`），
与 marker 不相等。

**证据 3（Theseus UI 面板：经 flow 收集器，栈里是 `h`/`k`）**：classes22.dex
`Lcom/bilibili/ship/theseus/united/page/speed/TheseusSpeedFunctionWidget$a;` 的 `b(F)V`
调用 `com.bilibili.ship.theseus.keel.player.h;->t(F)V`；`h.t(F)` 的字节码是

```
iget-object v0, h->b L…/PlaySpeedManagerImpl;      ← 只是取字段，类名不进栈
iget-object v0, PlaySpeedManagerImpl->a L…/StateFlowImpl;
invoke-virtual StateFlowImpl;->k(Ljava/lang/Object;Ljava/lang/Object;)Z   ← setValue
```

然后由 `com.bilibili.player.tangram.playercore.k.emit(Object,Continuation)V` 调用
`PCSFacadeImpl.b`（`Ltv/danmaku/biliplayerv2/service/A;`）的 `setPlaySpeed(F)V`。
这条路径上可能出现的是 `…theseus.keel.player.h`、`…playercore.k`，或协程调度器帧；
`PlaySpeedManagerImpl` 只在**别的**协程里（`a(F,Continuation)`，长按三倍速
`TripleSpeedService$runOldTripleSpeed$…` 用它），而且收集器是否在同一线程内联恢复
无法静态确定。即便长按路径碰巧命中，常规的「面板选一档」也命中不了。

**后果**（按 `SpeedHooks.decide` 推演）：开开关、配置 1.5x，用户点 2.0x →
`requested=2.0`、`manual=false`、`startup=false` → `onHostReset(1.5)` → **hook 把入参换成 1.5** →
宿主 pref / 内核 / 日志三处都是 1.5，但 Keel/UI 状态里是 2.0（Theseus 路径），表现为
「选了 2.0 实际播 1.5」。这与 README 第 30 行、RELEASE_NOTES v1.10.1「手动调速立即跟随」、
设置页文案（`SettingsActivity.java:156-161`）全部相反。

**为什么单测没抓到**：`tools/speedpolicy_test.py` 的 `isManualChange` 用例是**自己拼一个**
包含该字面类名的假栈（第 32 行 `MANUAL_CLS`、第 144-168 行），它只证明了「精确等值匹配」这个机械行为，
没有证明「宿主真的会把这个类放进栈里」。见 NIT-5。

**修复方向（供 lead 决策，不是本审计的改动）**：
1. 最小改法：把「非起播」的调用**默认放行**而不是改回配置值（即只有 `startup` 才动参数），
   代价是宿主自己复位速度后不会自动恢复默认值——但这与「只在起播套用」的文档口径一致；
2. 若一定要保留「手动跟随」：改在真正的手动入口上取信号（`h.t(F)` / 面板 widget 的设速调用），
   不要依赖 `eK1.q#setPlaySpeed` 的栈；长按三倍速与面板选档是两条不同的入口；
3. 或者删掉「立即跟随」的承诺（README/发行说明/设置页文案同步删），只保留「每次起播套用」。
   这是本版最省事且不会骗人的选项。

### BUG-2（低-中）多版本适配声明与「倍速只锚 6.7.1」不符

**结论**：模块描述与 README 都写「实测适配 6.3.0 / 6.4.0 / 6.5.0 / 6.6.0 / 6.7.1」，功能表里
倍速一行没有版本限定（`README.md:16,30`；`module_conf.py:8` 的描述串）。但 `SpeedHooks.CANDIDATE_HOLDERS`
只有 6.7.1 的 `eK1.q`（`SpeedHooks.java:97-99`），且 `L1` miss 后**只 warn、不枚举**（`SpeedHooks.java:142-156`）。
所以在 6.3.0–6.6.0 上这个开关多半是「开了但没效果」，用户只能在模块日志里看到一条
`speed: anchor L1 miss`。

**证据**：代码 + 文档如上；本机只有 6.7.1 的 dex，无法验证其它版本是否也有 `eK1.q`/同形类
（大概率没有，R8 混淆名逐版变）。**未验证部分**标注在 §7。

**建议**：README 功能表该行加「6.7.1」，或把「该版本未校准」写进设置页描述与发行说明。

### BUG-3（低，潜在）`SpeedSession.manual` 跨线程读写无同步

**结论**：`SpeedSession` 的注释说「实际调用都发生在播放回调线程上」（`SpeedSession.java:29`），
但手动调速来自 UI 线程（面板点击/长按），`onPrepared` 来自播放器回调线程；`manual` 是普通
`float` 字段（`SpeedSession.java:34`），没有 `volatile`/同步。**一旦 BUG-1 修好**，这里可能出现
「UI 线程写的 manual 值在播放线程不可见」的竞态（JMM 层面没保证），表现为偶尔回不到手动值。

**证据**：代码本身；两条调用来源的线程归属需要真机确认（`eK1.f#onPrepared` 由 IjkMediaPlayer
的 listener 回调触发，线程取决于宿主创建播放器的线程）。当前被 BUG-1 掩盖（manual 永远为 0）。

**建议**：`private volatile float manual;` 或给三个方法加 `synchronized`（状态机极短，开销可忽略）。

---

## 3. NIT（文档/命名/死代码不一致）

### NIT-1 PITFALLS §60 的「验收口径」是 v1.10.0 遗留，与当前代码不符

- 文档（`PITFALLS.md:758-760`）：「`speed: armed …(stage=L2)` → `armed …(stage=L4)` →
  `armed …(stage=L6)` → `armed …(stage=L6b)` → `speed: applied 1.50 (stage=L6 …)`；前四条每类只打首条」，
  并说「只有 armed 没有 applied ⇒ 起播没走到 resume」。
- 代码实际只有三段：`L1`（找类，只打 `anchor L1 hit hint` / `L1 miss`）、`L2`（setPlaySpeed，
  `armed …(stage=L2)`）、`L3`（R0 兜底，`armed …(stage=L3)`）；`applied` 行是
  `speed: applied 1.50 at start (was 1.00)`，**不带 stage**。`resume` 在 6.7.1 根本不存在。
- 判定：**过期的是 PITFALLS §60 的验收段**（沿用了 v1.10.0 那套 6 环链），代码的 L1/L2/L3 是现行。
  按这份文档去真机验收会找不到 `stage=L4/L6/L6b`，误判成「钩子没挂全」。

### NIT-2 SpeedHooks 类注释声称的「动态定位 / 按方法体认 R0」没有实现

`SpeedHooks.java:48-53` 写：「**不写死类名**：在宿主 ClassLoader 里按『哪个类声明了
`setPlaySpeed(float)`』动态定位」「`R0` … 在同类的 `(Z)F` 方法里按调用点特征认（方法体内引用
`player_key_video_speed` 或常量 1.0f 的那个）」。
实现是：只遍历 `CANDIDATE_HOLDERS`（`SpeedHooks.java:126-156`），miss 就 warn 返回；
`R0` 兜底只看「唯一的 `(Z)F`」（`SpeedHooks.java:216-231`）。`PREF_SPEED_KEY` 常量
（`SpeedHooks.java:90`）**声明后从未使用**——它就是那条没实现的「方法体特征」留下的痕迹。
`docs/speed-anchor-6.7.1.md:60-63` 也写着同样的未实现判据。
（PITFALLS §60 已改成「提示未命中时不猜」，与类注释矛盾，属于改了一半没清干净。）

### NIT-3 MainHook 装配处注释仍写 v1.10.0 的旧链

`MainHook.java:263-266`：「起播链（`notifyOnInfo → onPrepared → setSpeed`）跑在播放器所在的 Java 进程」。
`notifyOnInfo` 已被 PITFALLS §60 判定「不是起播入口」，注释应改为 `onPrepared → setPlaySpeed`。

### NIT-4 `SpeedPolicy.follows()` 是死代码，却被单测覆盖了 11 格

`follows()`（`SpeedPolicy.java:137-148`）在生产代码里**没有任何调用**（v1.10.0 的旧实现残留；
现行 `SpeedHooks` 用 `SpeedSession.onHostReset` 表达同一件事）。`tools/speedpolicy_test.py:171-198`
的 11 格 `follows` 用例因此只是在测死代码，让 61 格的「覆盖感」虚高。
建议删 `follows()` 与对应用例，或把它接回实现（二选一，别两留）。

### NIT-5 `speedpolicy_test.py` 的 manual 用例复刻了宿主类名，锚点错了也会绿

`speedpolicy_test.py:32` 自己定义 `MANUAL_CLS = "com.bilibili.player.tangram.basic.PlaySpeedManagerImpl"`，
并在假栈里塞这个类（`:144-168`），而不是引用 `SpeedPolicy.MANUAL_MARKER` 或验证真实宿主的栈。
所以 BUG-1（marker 在真实链路上不存在）在 61/61 全绿下完全不可见。
建议：用例名/注释里明确写「本用例只验证精确匹配的机械行为，锚点真实性由 dex 实证负责」，
或者把 marker 改成从实现里读（反射读 `SpeedPolicy.MANUAL_MARKER`）并补一条「锚点实证」的文档链接。

### NIT-6 PITFALLS §60 的 apply()/commit() 竞态解释不成立（commit 本身无害）

`PITFALLS.md:751-757` 说「`putInt(...).apply()` 是异步落盘，`persistAll → saveAll` 立刻读 SP，
两者赛跑会让 conf 里还是旧值」。**这个解释在 Android 上不成立**：`apply()` 先同步更新
内存里的 `mMap`（`commitToMemory()`）再异步入队落盘，同进程随后的 `getInt()` 一定读到新值；
`ConfSync.saveAll` 是同一个进程、同一个 `SharedPreferences` 实例。
（真正会发生的是「进程被立刻杀死时磁盘没落」，与这里的「读回旧值」不是一回事。）
代码用 `commit()`（`SettingsActivity.java:473`）本身没错，但注释的理由应改，
否则以后有人会以为 `apply()` 有读回竞态而到处改 `commit()`。
另外开关行仍用 `apply()`（`SettingsActivity.java:332`）——按上面的分析没问题。

### NIT-7 设置页文案「改后需强停 B 站重开」与热生效机制矛盾

`SettingsActivity.java:161` 的开关描述结尾写「改后需强停 B 站重开」，但本模块的配置通道是
「保存 → 拉起 B 站投递 → `onNewIntent` 热替换」（`ConfSync.java:250-294`、`MainHook#installConfDelivery`），
日志与状态行也在强调「已保存 / 宿主已确认」。同一页里两种口径会让用户以为必须重启。
（同页其它开关也有这句，属于既有文案习惯；但倍速是明确按「热生效」设计的那一个。）

### NIT-8 同一条件打了两条不同格式的 clamp 日志

读取到写入侧外（`pct < 25 || pct > 300`）时：
`MainHook.getPlaybackSpeedPct()` 打 `speed: conf pct=350 out of write range 25..300, clamped to 3.00`（warn，`MainHook.java:937-945`）；
`SpeedHooks.decide()` 又打 `speed: clamp pct=… -> …`（info，`SpeedHooks.java:276-281`）。
两条各去重一次，无害，但排障时同一件事两条不同措辞容易误读为两处问题。留一条即可（建议留 MainHook 的 warn）。

---

## 4. 单测独立复核（含变异实验）

原始结果（独立重跑，不是转述 lead 的）：

```
python3 tools/speedpolicy_test.py   → SUMMARY 61 passed, 0 failed (61 cases)
python3 tools/speedsession_test.py  → SUMMARY 19 passed, 0 failed (19 cases)
```

**用例是否真的覆盖文档语义**：覆盖了 `clamp`（含 NaN/±Inf）、`target`、`isManualChange` 的
`FRAME_SKIP=4`、`toPct/fromPct` 的写入侧 25..300 与读取侧 25..400、以及 `SpeedSession` 的 19 条事件序列
（起播/手动/宿主复位/暂停继续/配置热变更/连续手动）。**但三处关键缺口**：

1. 「手动跟随」的**锚点真实性**没有被测（见 NIT-5），这是 BUG-1 逃逸的原因；
2. `SpeedHooks.decide()` 本身（三分支优先级、L3 传 `startup=false`）没有单测（依赖 android，未抽纯函数）；
3. `follows()` 被测但生产不用（NIT-4）。

**「变异测试会红」的验证**（在 `/root/work/audit/mut` 的仓库副本上做，主工作区未污染）：

| 变异 | 结果 |
| --- | --- |
| `SpeedSession.onStartup` 删掉 `manual = 0f;`（字面理解「不清手动值」） | speedsession **2 failed**（`manual_does_not_leak_into_next_startup_then_reset`、`manual_readout_cleared_by_startup`） |
| 同上，且 `onStartup` 把 stale `manual` 传给 `target()`（PITFALLS 描述的那种改法） | speedsession **精确 4 failed**：`startup_after_manual_returns_to_configured`、`pause_resume_also_returns_to_configured`、`manual_does_not_leak_into_next_startup_then_reset`、`manual_readout_cleared_by_startup` → **与 PITFALLS §60「4 条精确失败」一致** |
| `SpeedPolicy.MAX 3.0f → 4.0f` | policy **6 failed**（clamp/fromPct/target 多处） |
| `SpeedPolicy.FRAME_SKIP 4 → 0` | policy **3 failed**（wrapper 帧被当成手动） |
| `SpeedPolicy.MANUAL_MARKER` 改成不存在的类 | policy **3 failed**（说明测试确实钉住了这个字面量） |
| `SpeedSession.onHostReset` 忽略 `manual` | speedsession **2 failed** |
| `SpeedPolicy.PCT_MAX 400 → 4000` | **0 failed**（用例只钉行为边界 3.0，不钉读取侧常量；无伤，记录一笔） |

结论：**「变异测试会红」成立**；PITFALLS §60 的「4 条精确失败」对应的是「不清 + 继续用 stale manual」
那种改法（我复现到了恰好 4 条），字面只删一行则是 2 条——不算错，但建议文档写清变异的具体形态。

---

## 5. 构建验证

**前置保护**：先把 `dist/` 里现有的 v1.10.0 / v1.10.1 备份到 `/root/work/backup/`（`cp -a`），
sha256 记录：

```
fba34b7669673b5441e43018f8f960a1042909e41c5303223d71d25c123ebe07  BiliTamer-v1.10.0.apk
fc948554e56b62db1c1b0838a0725bd4b9b47f4344aa02c8cc6dbd2f4a006da5  BiliTamer-v1.10.1.apk
```

**构建（08:22 快照）**：`cd /root/src/BiliTamer && python3 tools/build_module.py` → exit 0，关键输出：

```
==> compile module
==> dex
manifest bytes: 1672 ; resources.arsc bytes: 784 ; icon: True
unsigned apk: 168261 bytes
==> sign apk
SIGNED v1=true -> dist/BiliTamer-v1.10.1.apk
SIGNED MODULE APK: /root/src/BiliTamer/dist/BiliTamer-v1.10.1.apk  (176822 bytes)
```

**产物核对**（androguard `APK` + `unzip` + `jarsigner`）：

| 项 | 结果 |
| --- | --- |
| 包名 / versionCode / versionName | `com.tamer.bili` / `39` / `1.10.1` |
| minSdk / targetSdk | 26 / 34 |
| 入口 | `META-INF/xposed/java_init.list` = `com.tamer.bili.MainHook` |
| libxposed 元数据 | `META-INF/xposed/module.prop`：`minApiVersion=101`、`targetApiVersion=102`、`staticScope=true`；`META-INF/xposed/scope.list` = `com.bilibili.app.in` |
| classic 元数据 | 无 `assets/xposed_init`（本模块是 libxposed 模式，符合预期） |
| 签名 | `jarsigner -verify` exit 0；APK 内有 `APK Sig Block 42`（v2/v3 签名块；builder 声明 apksig v1+v2+v3）；`META-INF/MODDER.SF/.RSA` 齐全 |
| classes.dex | 231 个类；`SpeedHooks` / `SpeedPolicy` / `SpeedSession` / `LogRing` / `LogExport` 均在；**没有**单测探针类（`SpeedPolicyProbe` 等） |
| 其它 | `dist/BiliTamer-v1.10.0.apk`、`v1.9.9.apk` 未被覆盖（mtime 仍是 10 月 31 日） |

> **快照声明**：上表验证的是 08:22 的构建产物（176,822 bytes，sha256 `2981bc9d…`）。
> 08:29 起该文件被并行会话用「含档位表」的新代码重新构建覆盖（180,918 bytes），
> 08:33 又出了 `v1.10.2`。**本次没有验证 1.10.2 的产物**；按 §0 的要求，发版前需在树稳定后
> 重新跑一次 `python3 tools/build_module.py` + `jarsigner -verify` + 入口/版本核对。

**libxposed API 用法核对**（对 `tools/libxposed/api.aar` 的 `classes.jar` 用 `javap`，
版本 102.0.0，见 `tools/libxposed/PROVENANCE.md`）：

- `XposedInterface$Chain` 有 `getArg(int)`、`proceed()`、`proceed(Object[])`、`proceedWith(...)`——`SpeedHooks` 用的
  `chain.getArg(0)`（`SpeedHooks.java:164`）和 `chain.proceed(new Object[]{Float.valueOf(want)})`（`:187`）签名正确；
- `XposedInterface.deoptimize(Executable)` 存在，`MainHook.deoptimize(Method)` 走 `Executable` 重载正确；
- `HookBuilder.setPriority(int)` / `setExceptionMode(ExceptionMode)` / `intercept(Hooker)` / `HookHandle` 都存在，
  `MainHook.addHook` 的链式用法可编译（构建已证）；
- `ExceptionMode.PROTECTIVE` 的语义是「钩子抛异常不破坏宿主调用」，与 `SpeedHooks` 内部 `try/catch` 双保险一致
  （该语义来自 API 枚举与 libxposed 文档，本机只验证了签名可编译，未在框架上运行）。

---

## 6. 装配位置与失败隔离（任务 4）

- **只在主进程装**：`SpeedHooks` 的 `install("SpeedHooks", …)` 位于 `MainHook.installFeatures()` 的
  `if (main) { … }` 块内（`MainHook.java:267-271`）；`main = hostPkg.equals(processName)`
  （`MainHook.java:79`）。`:ijkservice` / `:download` / `:web` 不会装，符合注释里的进程分工。
- **受 master 开关保护**：`onPackageReady` 在 `installFeatures()` 之前就
  `if (!config.get(KEY_MASTER, true)) return;`（`MainHook.java:95-98`），关掉总开关时不会装任何功能钩子，
  包括 SpeedHooks。
- **失败隔离**：`install(name, action)` 捕获 `Throwable` 后只打 `error("hook group unavailable: …")`
  并继续（`MainHook.java:553-560`）；`SpeedHooks.install()` 内部对 L1/L2/L3 每段也各自 try/catch，
  L2 hook 失败会 `return` 只跳过 L3，不影响其它功能组。**不会因为倍速装不上而影响 IP 属地/解码/首页等**。
- **热生效**：`isPlaybackSpeedEnabled()` / `getPlaybackSpeedPct()` 每次都从 `volatile config` 现读
  （`MainHook.java:924-955`），没有快照缓存；ConfSync 投递整表替换 config 后，下一次 `setPlaySpeed`
  就能看到新值。符合「改了不用重启」的设计。
- **重入**：hook 只改入参、不回调宿主方法；`setPlaySpeed` 方法体里对监听器的通知
  （`sK1/e` 的 `U.a(F)`）实测只做埋点/状态记录（classes28.dex 里两个实现都不调用 `setPlaySpeed`），
  不会形成 ping-pong 递归。ConfSync 与 hook 之间没有共享锁，只有对 `volatile config` 的读 →
  责任链干净（`SpeedSession` 的线程问题见 BUG-3）。

---

## 7. 无法静态验证、必须真机确认的点

1. **`onPrepared` 是否在真机主播放器上触发**：dex 证明 `eK1.f#onPrepared → eK1.q#setPlaySpeed` 无条件相邻，
   也证明了 Theseus 适配器最终仍把速度落到 `eK1.q#setPlaySpeed`；但 6.7.1 同时存在 Theseus 新容器，
   实际跑哪条容器路由是运行期决定的。
2. **`param.getClassLoader()` 能否 `loadClass("eK1.q")`**：`classes28.dex` 是 base APK 的 base dex，
   静态上应当能加载；若宿主对播放器核心用了独立 ClassLoader 则不能（静态排除不了）。
3. **「手动跟随」修复后的真实栈**：本报告已证明两条常规手动链路的栈里没有 marker；
   但长按三倍速（`PlaySpeedManagerImpl.a`）是否会内联恢复、栈里会不会出现该类，
   取决于协程 dispatcher，只有真机能看。
4. **hook 有没有真正改变听感速率**：native `libijkplayer` 3.0x 硬钳制、2.0→1.99 转换都是宿主行为，
   需要真机在 0.25x–3.0x 各档实测。
5. **`SpeedSession` 的线程可见性**（BUG-3）：需要真机看 `onPrepared` 与面板回调是否同一线程。
6. **其它自称支持版本（6.3.0–6.6.0）**：本机只有 6.7.1 的 dex，`eK1.q` 在那些版本上是否存在无法验证（BUG-2）。
7. **@conf 热投递是否被 ROM 拦截**：与倍速无关但决定「改完是否立刻生效」，属既有 ConfSync 通道的已知变量。

---

## 8. 复算命令（留档）

```bash
# 1) 备份现有产物（先做）
mkdir -p /root/work/backup && cp -a /root/src/BiliTamer/dist/BiliTamer-v1.10.{0,1}.apk /root/work/backup/

# 2) dex 实证（androguard 4.1.4）
/root/work/venv/bin/python - <<'PY'
from androguard.core.dex import DEX
d = DEX(open('/root/work/apk/base/classes28.dex','rb').read())
for c in d.get_classes():
    if c.get_name() == 'LeK1/q;':
        for m in c.get_methods():
            if m.get_name() in ('setPlaySpeed','R0'):
                print(c.get_name(), m.get_name(), m.get_descriptor(), m.get_access_flags_string())
                for i in m.get_code().get_bc().get_instructions():
                    print('  ', i.get_name(), i.get_output())
PY
# 同类里 (Z)F 计数（应为 1）、onPrepared 链、PlaySpeedManagerImpl 方法表、widget 设速入口
# 均在 /root/work/audit/dex_*.py 里（本轮审计脚本，可复跑）

# 3) 单测
cd /root/src/BiliTamer && python3 tools/speedpolicy_test.py && python3 tools/speedsession_test.py

# 4) 构建 + 产物核对
cd /root/src/BiliTamer && python3 tools/build_module.py
unzip -p dist/BiliTamer-v1.10.1.apk META-INF/xposed/java_init.list
unzip -p dist/BiliTamer-v1.10.1.apk META-INF/xposed/module.prop
jarsigner -verify dist/BiliTamer-v1.10.1.apk   # exit 0
```

---

## 9. 并行修正观察（v1.10.2 草稿，08:34 起出现在工作区）

审计收尾时（08:34–08:36）观察到同一工作区里另一会话已按「非起播一律放行」重写了默认倍速：

- `SpeedHooks` 去掉 `PlaySpeedManagerImpl` 手动判定与 L3 `R0` 兜底，`decide()` 只剩「关闭 → 起播 → 非起播放行」三支；
- `SpeedPolicy` 改成 `startupSpeed(enabled, pct)`，删除 `isManualChange/follows/target`；
- `SpeedSession.java` 删除（不再需要手动态，BUG-3 的跨线程问题随之消失）；
- `tools/speedpolicy_test.py` 由 61 格改为 39 格，`tools/speedsession_test.py` 删除。

这与本报告 BUG-1 的修复建议第 1 条一致，从设计上移除了「把用户手选值改回配置」的失效面：
用户选档 → `setPlaySpeed(requested)` 非起播 → 原样放行（立即生效）；下一次起播 → 改配置值（不记忆）。

**但我没有审计这份草稿的最终形态**（文件仍在写，docs/tests/build 尚在同步），且它带来几处必须同步的点：

1. README 仍写 `61 格` / `19 格` / `speedsession_test.py`（`README.md:131-132,152`、`RELEASE_NOTES.md:62,104`），
   与新测试不符；
2. PITFALLS §60 的验收口径、`docs/speed-anchor-6.7.1.md` 的「R0 兜底落点」、`SpeedHooks` 里 `stage=L3`
   的描述，都要随 L3 删除一起更新；
3. 需要重跑 `tools/speedpolicy_test.py`（39 格）+ 重新构建/验签（当前 `dist/` 的 1.10.2 由另一会话产出，
   本报告未验证）；
4. 新 `startupSpeed(true, pct)` 仍经过 `fromPct` 的读取侧 clamp（25..400），行为边界不变，✅；
5. 建议树稳定后按 §4/§5 同一套命令复核一次 v1.10.2，并对新草稿的 `SpeedMenuHooks`（自定义档位表）
   另做一次锚点实证（本报告未覆盖）。

---

## 10. 结论

- **锚点成立**：6.7.1（9161100）classes28.dex 的 `eK1.q#setPlaySpeed(F)V`、`eK1.q#R0(Z)F`
  （pref `player_key_video_speed` + 1.0f 常量）、`eK1.f#onPrepared` 无条件相邻调用链，逐条实证通过；
  「形状兜底」在该类上只有唯一 `(Z)F`，不会误命中；类名与 dex 拼写一致。
- **构建通过（08:22 快照）**：v1.10.1 APK 可生成、v1+v2/v3 签名可验、包名/版本/入口/作用域元数据正确，
  产物结构干净；但当前 `dist/` 已被并行会话的 1.10.2 构建覆盖，发版前需重跑一次（§0/§5）。
- **单测可信但覆盖有洞（审计快照）**：61/61、19/19 全过，变异实验能红（含 PITFALLS 说的 4 条）；
  但手动锚点的真实性没有被测，正是 BUG-1 逃逸的原因。
- **BLOCKER = 0**；对**审计快照（v1.10.1）**必须先处理 BUG-1（要么修，要么删掉「手动跟随」的承诺），
  否则真机上会出现「用户选的倍速被模块改回去」，比「不生效」更难解释。
- **收尾时另一会话已在 v1.10.2 草稿中按「非起播一律放行」修掉 BUG-1**（§9）。这是正确方向，
  但那份草稿没有经过本报告的完整构建/单测复核；建议树稳定后按 §4/§5 命令再跑一遍，
  并单独审计新加的 `SpeedMenuHooks`（自定义档位表）。

---

## 11. v1.10.2 复核（2026-11-08 08:37 快照，按 lead 要求追加）

**被复核对象**（sha256）：

| 文件 | sha256 |
| --- | --- |
| `hooks/SpeedHooks.java` | `766a12de…` |
| `hooks/SpeedPolicy.java` | `2ed879f6…` |
| `hooks/SpeedListPolicy.java` | `be3113bc…` |
| `hooks/SpeedMenuHooks.java` | `d07eb47b…` |
| `tools/speedpolicy_test.py` | `1eccd17d…` |
| `tools/speedlistpolicy_test.py` | `0d59201e…` |
| `dist/BiliTamer-v1.10.2.apk` | `5efeb77f…`（180,918 bytes） |

**总判定：v1.10.1 的 BUG-1 已从设计上修掉；新增「自定义倍速档位表」的锚点独立实证成立；
本轮重新构建/签名/入口/单测全部通过；未发现 BLOCKER；只剩 4 条 NIT（见 11.7）。**

### 11.1 改法核对（SpeedHooks / SpeedPolicy）——✅ 成立

- 只剩 L2 一个改写点；`decide()` 三支：未启用 → 原样、**非起播 → 原样**、起播 → `startupSpeed`。
  改写面从 v1.10.1 的「三类调用都改写」缩到「**仅起播且值真的不同**」，手动选档走非起播分支
  原样放行，不会再被改回去——BUG-1 的因果链（误判手动 → onHostReset → 改回配置值）整条消失。
- 无共享可变状态（只 `AtomicBoolean` 日志去重 + volatile `config`），无状态机、无跨线程手动值，
  v1.10.1 的 BUG-3（`manual` 跨线程可见性）随之消失。
- 无自我递归：hook 不回调宿主方法；`setPlaySpeed` 内部的 `U.a(F)` 监听器在 classes28.dex 里只做埋点，
  不回到 `setPlaySpeed`（§2.0 已核）；`Float.compare(want, requested)==0` 时不替换。
- `R0` 兜底钩子已删；复核确认 6.7.1 里 `R0` 的结果只会被 `eK1.f#onPrepared` 立刻喂给 `setPlaySpeed`，
  没有绕过 `setPlaySpeed` 直接生效的路径——删它是安全的，同时消除了「改 R0 返回值会让面板显示值
  与实际值不一致」的副作用。
- 小问题（NIT-12）：`isStartupCall(Thread.getStackTrace())` 在「开关是否开启」判断**之前**执行
  （`SpeedHooks.java:137`，`decide()` 里才查 `isPlaybackSpeedEnabled`），所以出厂默认关时
  每次 `setPlaySpeed` 仍要抓一次栈。建议先查开关再抓栈（纯开销，不影响正确性）。

### 11.2 新档位功能锚点（独立于 lead 的 menu-recon 复核）——✅ 成立

classes18.dex：

- `Lcom/bilibili/playerbizcommonv2/utils/B;`：`public static final List a` +
  `public static a(MediaResource; Integer; Z I)Ljava/util/ArrayList;` —— 与 `SpeedMenuHooks.findBuilder`
  的四项形状校验（静态、4 参类型、返回 ArrayList、同类有静态 List 字段）逐项吻合；
- `B.<clinit>`：`listOf(2.0f, 1.5f, 1.25f, 1.0f, 0.75f, 0.5f)` → 静态字段 `a`；
- `B.a` 方法体：先按实验组（`LongPressSpeedExperiment`）+「是否下载」决定 3.0 档的 `enabled`，
  `new t(3.0f, enabled)` 加入，再遍历静态 `a` 对每档 `new t(speed, true)`，**每次 `new ArrayList()`**，
  最后返回——「每次开面板重建、无缓存」与注释一致，改完设置重开面板即可见新档位；
- 元素类 `Lcom/bilibili/playerbizcommonv2/utils/t;`：字段 `a:F` / `b:Z`、构造器 `(F Z)V` —— 与
  `itemCtorOf` 的 `(float, boolean)` 传播一致；
- **静态调用点实测 5 处**（classes18 的 `widget.speed.e`、`PlayerSettingFunctionWidget2`；
  classes22 的 `TheseusSpeedFunctionWidget`、其 lambda、`MenuService`）——「三个调用者」的措辞不准确
  （NIT-10），但 5 处都经由这一个方法取档位表，「挂一处覆盖旧/新两条 UI」的结论成立。

### 11.3 新档位功能的改写面 / 线程风险

- `chain.proceed()` 在 try **之外**：宿主抛异常原样传播（不吞宿主错误）；`rewrite()` 抛异常回退原列表 ✓；
- 配置为空 → `compose()` 返回空数组 → 直接返回宿主原列表（零干预）✓；
- 元素构造器取不到 / 元素类反射失败 → `warnMiss` 一次 + 原样返回 ✓；
- 线程：`itemCtor` / `lastApplied` 都是 volatile，只有性能意义的无锁竞争，没有可见性 bug；
  改写发生在调用 `B.a` 的线程上，不跨线程持有宿主对象 ✓；
- 风险 1（NIT-11）：替换整张表时所有条目都是 `t(speed, true)`，**把宿主对 3.0 档的 gate
  （实验组 + 非下载）一并绕过**——在宿主本来不给 3.0 的场景（如离线下载）会变成可选。
  3.0 本身是合法速率且 native 会钳制，最坏是观感/同步问题，不是崩溃；建议类注释与设置页文案写清，
  或对同速条目保留宿主的 `enabled`；
- 风险 2（NIT-11）：`SpeedListPolicy.parse` 注释说「`1,5` 这种稿子丢弃」，实测
  `parse("1,5") = [3, 1]`（逗号是先行的分隔符，永远到不了 `Float.parseFloat`）——
  欧洲小数逗号会被拆成两档。设置页文案已写「逗号/空格分隔」，行为可接受，但注释要改；
- `itemCtor` 缓存不按元素类做键：当前只有一个 `B.a` 入口、元素恒为 `t`，无实际风险；
  若将来同方法返回多种元素类型才会出问题（记一笔，不改）。

### 11.4 单测与变异（独立重跑）

```
python3 tools/speedpolicy_test.py      → SUMMARY 39 passed, 0 failed
python3 tools/speedlistpolicy_test.py  → SUMMARY 34 passed, 0 failed
```

- 已无 `isManualChange` / `follows` / 假栈用例（grep 确认），即 v1.10.1 的 NIT-4 / NIT-5 已消除；
  39 格覆盖 clamp / startupSpeed（含 25/300 边界、读取侧 350/10 兜底）/ toPct / fromPct，
  34 格覆盖档位表的解析/去重/夹取/排序/截断/compose。
- 变异抽查（`/root/work/audit/mut2` 副本，主工作区未动）：

| 变异 | 结果 |
| --- | --- |
| `SpeedPolicy.MAX 3.0f → 4.0f` | policy **5 红** + listpolicy **3 红** |
| `startupSpeed` 恒返回 `NORMAL` | policy **6 红** |
| `compose` 忽略 `includeDefault` | listpolicy **1 红** |
| 降序改升序（截断保留小的） | listpolicy **11 红** |

  新用例能抓住逻辑变异，不是「全绿摆设」。

### 11.5 `onPrepared` 帧缺失的风险与后果（lead 专项问题）

**机制**：hook 在 `eK1.q#setPlaySpeed` 内部执行；dex 字节码证明该调用由 `eK1.f#onPrepared`
**直接发起**（classes28.dex `eK1/f.onPrepared` 第 11 条指令），且 `setPlaySpeed` 已被 deoptimize，
自身不会被内联。所以正常路径下 `onPrepared` 必然是其调用者栈帧。

**唯一能让帧消失的机制**：ART 把 `eK1.f#onPrepared` 内联进它的调用者（`AbstractMediaPlayer.notifyOnPrepared`
那条分发链），同时 `Thread.getStackTrace()` 不还原内联帧。概率**低**：

- 该调用是 `invoke-interface IMediaPlayer$OnPreparedListener.onPrepared`，实现类不止一个
  （`eK1.f` / `MediaPlayerProxy$1` / `AndroidMediaPlayer$…` / `lL1.b` / splash event），难以去虚化内联；
- `onPrepared` 方法体 67 条指令、含多次跨类调用与分支，不属于会被内联的小方法。

但**不能静态证明概率为零**（ART 的 JIT 会做 CHA/去虚化，且不同 ROM 的 ART 版本策略不同）。

**若帧缺失会怎样**：`startup=false` → 走「非起播」分支**原样放行** → 起播不再套用配置值 =
用户感知「开关开了但一直是一倍速」，不崩、不报错。日志特征：`speed: armed …` 有、
`speed: applied …` 永不出现；首次非起播调用会有一条 **debug**
（`speed: pass through non-startup call … from <调用者>`，`SpeedHooks.java:176`），
verbose 关时看不到——于是「帧缺失」与「这个视频根本没走 onPrepared」在用户日志里不好区分。

**建议（1 行日志，低风险）**：把首次 pass-through 那条 debug 提升为 info（或在累计 N 次 `setPlaySpeed`
仍从未见过 `onPrepared` 时打一条 warn），这样「armed 但从未 applied」可直接定位。更彻底的做法是
额外 hook 一个 onPrepared 入口置 `ThreadLocal` 标记（要按形状找 listener 实现类，多一个锚点）；
当前栈名匹配在 6.7.1 已够用，建议保留 + 补日志。

### 11.6 构建验证（本轮重跑）

- `python3 tools/build_module.py` → exit 0，签名产物 `dist/BiliTamer-v1.10.2.apk`（180,918 bytes，
  sha256 `5efeb77f…`）；
- `jarsigner -verify` exit 0；APK 内有 `APK Sig Block 42`（v2/v3 签名块）；
- androguard 读包：`com.tamer.bili` / versionCode **40** / versionName **1.10.2** / minSdk 26 / target 34；
  `META-INF/xposed/java_init.list` = `com.tamer.bili.MainHook`；
  `module.prop` = `minApiVersion=101, targetApiVersion=102, staticScope=true`；
  `scope.list` = `com.bilibili.app.in`；
- `classes.dex` 235 个类，`SpeedHooks` / `SpeedPolicy` / `SpeedListPolicy` / `SpeedMenuHooks` /
  `LogRing` / `LogExport` / `MainHook` 全在；无单测探针类；产物 10 个条目结构与 v1.10.1 一致。

### 11.7 v1.10.2 剩余问题清单

| 编号 | 档位 | 结论 + 证据 |
| --- | --- | --- |
| NIT-9 | NIT | `docs/speed-anchor-6.7.1.md:51-52,60-63` 仍写「兜底落点：`eK1.q#R0(boolean)` after」和「按方法体特征找 R0」——L3 已删、PITFALLS §60 已改，这份证据文档没同步，会让下一次校锚点的照着做无用功。 |
| NIT-10 | NIT | `SpeedMenuHooks.java:26` 写「三个调用者」，dex 实测静态调用点 5 处（classes18 两处、classes22 三处）；结论（挂一处覆盖两条 UI）不变，改措辞即可。 |
| NIT-11 | NIT | ①`SpeedListPolicy.java:82` 注释「`1,5` 丢弃」不成立，实测 `parse("1,5") = [3, 1]`；②`SpeedMenuHooks` 替换表所有条目 `enabled=true`，绕过宿主 3.0 档 gate（实验组/非下载）——两处都要在注释/设置页文案写清（或对同速条目保留宿主 enabled）。 |
| NIT-12 | NIT | `SpeedHooks.java:137` 先抓栈后查开关（默认关时白抓）；首次 pass-through 日志是 debug（见 §11.5 建议提为 info）。 |

### 11.8 仍需真机确认（v1.10.2）

1. 播放器倍速面板里是否真的出现自定义档位（静态链路成立 ≠ 面板显示正确；不同 ROM/主题的
   Compose/RecyclerView 面板均取 `B.a`，但实际渲染未验）；
2. 起播是否出现 `speed: applied …`（即 `onPrepared` 帧是否如预期在栈上，见 §11.5）；
3. 3.0 gate 被绕过后的实际后果（尤其离线下载视频）；
4. 真机上「手动选档立即生效、下一次起播回配置值」的端到端表现（本报告只能证明代码语义）。
