# 6.7.1 播放器倍速菜单「档位列表」锚点实证记录

> 宿主：哔哩哔哩国际版 **6.7.1**，`com.bilibili.app.in`，versionCode **9161100**
> APK：`/root/work/apk/xapk/com.bilibili.app.in.apk`（349 MB，34 个 dex）
> 工具：androguard 4.1.4（`DEX` 解析 + 指令流）+ 本文档配套脚本 `/root/work/recon/`
> 性质：**纯静态分析，未上真机验证**（`adb devices` 为空）。文中凡「实证」= 可在 dex 里复算到的
> 类/方法/指令偏移；凡「推断」= 由控制流形状推出、未动态验证。

---

## 0. 一句话结论

6.7.1 的倍速档位**不是**写死在 UI 里、也**不是**服务端下发，而是由一个工具方法现场拼出来：

```
com.bilibili.playerbizcommonv2.utils.B#a(MediaResource, Integer, boolean, int) : ArrayList
  ├→ （条件成立时）add(new t(3.0f, enabled))        // 3.0 档，受实验组 + 下载态 gate
  └→ 遍历静态字段 B.a : List<Float>                  // [2.0, 1.5, 1.25, 1.0, 0.75, 0.5]
       每档 add(new t(speed, true))
```

- **档位值是硬编码常量数组**（`<clinit>` 里 6 个 `const/high16`），`3.0` 是额外一条、受实验开关约束。
- **元素类是 Kotlin data class** `com.bilibili.playerbizcommonv2.utils.t`
  （字段 `a:F` = 速度、`b:Z` = 是否可用；构造器 `<init>(F Z)V`）。
- **三个 UI 路径全部经由这一个方法的返回值**：旧 biliplayerv2 的 RecyclerView 面板、
  theseus 的 Compose 面板、以及一个静态中转（`kotlin.collections.Z` 包一层）。
- 方法**每次调用都 `new ArrayList()`，无缓存**；面板每次打开都会重取。
- 因此最佳落点 = **hook `B.a(...)` 的 AFTER，整表替换返回值**（不碰 `static final` 字段、
  不碰适配器、不碰 Compose 状态）。实现见 `app/src/main/java/com/tamer/bili/hooks/SpeedMenuHooks.java`。

---

## 1. 复算命令（从 xapk 到看见这段代码）

全部脚本都在 `/root/work/recon/`，按下面顺序跑即可复现本文每一个数字。

```bash
# 0) 解包（APK 已经在 /root/work/apk/，这里只是留档）
unzip -o com.bilibili.app.in.apk -d base 'classes*.dex'
unzip -o com.bilibili.app.in.apk resources.arsc          # 只在需要查字符串资源时

# 1) 建「类名 → dex」快索引（8 秒，283,876 个类）
/root/work/venv/bin/python /root/work/recon/fastdex.py index
/root/work/venv/bin/python /root/work/recon/fastdex.py cls 'widget/speed'     # 看混淆包里的速度 UI 家族
/root/work/venv/bin/python /root/work/recon/fastdex.py cls 'page/speed'       # theseus 面板家族

# 2) 全 dex 扫「倍速档位浮点常量」（34 dex 约 4 分钟）
#    命中即「方法体里出现 0.5/0.75/1.25/1.5/2.0/3.0 这些 32 位 float 位型」
/root/work/venv/bin/python /root/work/recon/01_scan_speed_consts.py
#    输出 out/speed_const_hits.json；按「非 1.0 常量个数」排序就能一眼看到候选：
#    L.../utils/B;<clinit> 与 L.../widget/speed/f;<clinit> 都是 5 个

# 3) 精确反汇编单类（带指令偏移，单位 = 16 位码元）
/root/work/venv/bin/python /root/work/recon/04_smali.py 'com/bilibili/playerbizcommonv2/utils/B;' --dex classes18.dex
/root/work/venv/bin/python /root/work/recon/04_smali.py 'com/bilibili/playerbizcommonv2/utils/t;' --dex classes18.dex
/root/work/venv/bin/python /root/work/recon/04_smali.py 'Lcom/bilibili/playerbizcommonv2/widget/speed/f;' --dex classes18.dex
/root/work/venv/bin/python /root/work/recon/04_smali.py 'Lcom/bilibili/ship/theseus/united/page/speed/e;' --dex classes22.dex

# 4) 全 dex xref：谁调用 B.a / 谁读 B.a 字段 / 谁 new t（约 4 分钟）
/root/work/venv/bin/python /root/work/recon/05_xref.py --file /root/work/recon/queries_B2.json --out xref_B2.json
#    快速版（先用 method_id 表窄化到 1 个 dex，秒级）：
/root/work/venv/bin/python /root/work/recon/06_xref_narrow.py 'Lcom/bilibili/playerbizcommonv2/utils/B;' a

# 5) 其它小工具
#   07_find_field_readers.py <dex> <类描述符> <字段名>   查「谁读这个字段」
#   09_show.py（参数写在 args.txt 里）                  绕开 shell 对 $ 的展开，dump 内部类
```

> 坑：`const/high16` 的 `get_literals()` 在 androguard 4.1.4 里**已经是完整 32 位真值**
> （实证：raw `1502 803f` → `get_literals()=[1065353216]=0x3F800000=1.0f`），不要再 `<<16`，
> 否则一个都命中不了。这行踩过一次，脚本里已注释。

---

## 2. 真实链路表（自上而下）

| 层 | 符号（描述符精确） | dex | 证据 / 偏移 |
| --- | --- | --- | --- |
| 工具栏菜单 | `MenuService#d(MenuService)Lkotlin/collections/Z;` | classes22 | 0x005a `const/16 8` → 0x0060 `invoke-static B->a`；0x0068 `new kotlin/collections/Z` 包一层 List |
| 工具栏菜单项 | `MenuService$createSpeed$2$1$2$1#invokeSuspend(Object)Object` | classes22 | 0x001a 调 `MenuService.d`；0x0076 `iget-boolean t.b` 算 `disabledIndexes` |
| 面板（新，Compose） | `TheseusSpeedDialogPanel$createContentView$1$1$1$1$1#invokeSuspend` | classes22 | 调 `theseus/page/speed/e#b(e)` → `MutableState<ArrayList>` |
| 面板状态（新） | `theseus/page/speed/e#b(L.../speed/e;)Ljava/util/ArrayList;` | classes22 | 0x005a `const/16 8`；0x0060 `B.a(media, Integer(playIndex), true, 8)` |
| 面板（新，Widget 式） | `TheseusSpeedFunctionWidget#f()V` | classes22 | `B.a(media, Integer, true, 8)` → `speed/f#e0(F, ArrayList)` |
| 面板（旧） | `com.bilibili.playerbizcommonv2.widget.speed.e#onWidgetShow()V` | classes18 | 0x00aa `const/16 14`；0x00ae `B.a(media, null, false, 14)` → `speed/f#e0` |
| 播放设置面板 | `com.bilibili.playerbizcommonv2.widget.setting.PlayerSettingFunctionWidget2#onWidgetShow()V` | classes18 | `B.a` xref 命中 |
| 竖屏故事面板 | `com.bilibili.video.story.action.widget.StoryLandscapeSpeedWidget#i0(StoryPlayer)` | classes21 | 返回 `ArrayList`，同样来自 `B.a` |
| 故事设置菜单 | `com.bilibili.video.story.setting.F#f(F)Ljava/lang/Object` | classes21 | `B.a` xref 命中 |
| **档位表构造（真源）** | **`B#a(MediaResource,Integer,Z,I)Ljava/util/ArrayList;`** | **classes18** | 见 §3 |
| 静态档位常量 | `B.a : Ljava/util/List;`（`<clinit>`） | classes18 | 见 §3，6 个 `const/high16` |
| 元素类 | `t`，`<init>(F Z)V`，字段 `a:F` / `b:Z` | classes18 | 0x0000 ctor；0x0006 `iput a`；0x000a `iput-boolean b`；`toString` 字符串 `"PlaybackSpeedOption(speed="` |
| 旧面板适配器 | `com.bilibili.playerbizcommonv2.widget.speed.f` extends `RecyclerView$Adapter` | classes18 | `<clinit>` 同 6 个常量（静态 `e:List`）；`e0(F,ArrayList)`；`onBindViewHolder` |
| 点击→设速（旧/Widget） | `TheseusSpeedFunctionWidget$a#b(F)V` → `keel/player/h#t(F)V` | classes22 | `h.t(F)` → `PlaySpeedManagerImpl.a`(StateFlow) 写值 |
| 点击→设速（新/Compose） | `Lcom/bilibili/ship/theseus/united/page/speed/e` 的 lambda | classes17 | R8 合并 lambda `CB0/a#invoke` 的 0x05d8 处 `h.t(F)` |
| 倍速管理器 | `com.bilibili.player.tangram.basic.PlaySpeedManagerImpl` | classes18 | 字段 `a:StateFlowImpl`（ctor 0x0006 `const/high16 1.0f` 初值）、`b:StateFlowImpl`、`c:Mutex`；`a(F,Continuation)`、`k()Lkotlinx/coroutines/flow/d;` |
| 下发 | `eK1.q#setPlaySpeed(F)V` | classes28 | 见 §6 |
| 内核 | `tv.danmaku.ijk.media.player.IjkMediaPlayer#setSpeed(F)V` | — | PITFALLS §14/§15 已有记录，本文不重复 |

---

## 3. 档位列表到底怎么产生的（dex 实证）

### 3.1 静态档位常量：`B.<clinit>`（classes18.dex）

```
0000: const/high16  v0, 1073741824   ; 0x40000000 = 2.0f
000c: const/high16  v0, 1069547520   ; 0x3FC00000 = 1.5f
0018: const/high16  v0, 1067450368   ; 0x3FA00000 = 1.25f
0024: const/high16  v0, 1065353216   ; 0x3F800000 = 1.0f
0030: const/high16  v0, 1061158912   ; 0x3F400000 = 0.75f
003c: const/high16  v0, 1056964608   ; 0x3F000000 = 0.5f
0048: filled-new-array/range v1 ... v6, [Ljava/lang/Float;
0050: invoke-static  v0, Lkotlin/collections/v;->k([Ljava/lang/Object;)Ljava/util/List;   ; listOf(...)
0058: sput-object    v0, Lcom/bilibili/playerbizcommonv2/utils/B;->a Ljava/util/List;
```

⇒ `B.a = listOf(2.0f, 1.5f, 1.25f, 1.0f, 0.75f, 0.5f)`，**降序、6 档、硬编码**。
浮点位型按任务书给的换算表逐个核对过（0x40000000=2.0 / 0x3FC00000=1.5 / 0x3FA00000=1.25 /
0x3F800000=1.0 / 0x3F400000=0.75 / 0x3F000000=0.5）。

### 3.2 列表构造：`B#a(MediaResource, Integer, Z, I) : ArrayList`（classes18.dex，方法体节选）

```
0028: new-instance   v2, Ljava/util/ArrayList;          ; 每次调用新建 → 无缓存
0038: sget-object    v3, LongPressSpeedExperiment;->SmartLongPressAnd3x
0046: sget-object    v3, LongPressSpeedExperiment;->Speed2And3x     ; ← 3.0 档的实验 gate
0066: const-string   v3, "downloaded"                              ; ← 下载态 gate
00d2: invoke-static  v5, v6, v0, B;->e(Lcom/bilibili/lib/media/resource/MediaResource; I Z)F
00da: invoke-static  v5, B;->c(F)Z                                 ; 0 < x < 50 ?
00e8: const/high16   v5, 1077936128   ; 0x40400000 = 3.0f
00ec: invoke-direct  v8, v5, v0, Lcom/bilibili/playerbizcommonv2/utils/t;-><init>(F Z)V
00f2: invoke-virtual v2, v8, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
00f8: sget-object    v5, B;->a Ljava/util/List;
0100: invoke-interface v5, Ljava/lang/Iterable;->iterator()
0128: new-instance   v7, Lcom/bilibili/playerbizcommonv2/utils/t;
012c: invoke-direct  v7, v6, v4, Lcom/bilibili/playerbizcommonv2/utils/t;-><init>(F Z)V   ; (Float.floatValue(), true)
0132: invoke-virtual v2, v7, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
0138: goto           -18h                                            ; 循环
013a: return-object  v2
```

语义（**实证 + 少量推断**）：

1. `new ArrayList()` → 无缓存；
2. 若实验组 ∈ {`SmartLongPressAnd3x`, `Speed2And3x`} 且 `PlayIndex.b != "downloaded"`，
   先加一条 `new t(3.0f, B.c(B.e(media, idx, flag)))`；
   （分支走向按指令形状推断，`"downloaded"` 与两个枚举常量是实证；
   这段的价值在于：**3.0 档是额外一条，不在静态数组里**。）
3. 再遍历静态 `B.a`，每档 `new t(f, true)` 追加；
4. 因此完整列表 = `[3.0?, 2.0, 1.5, 1.25, 1.0, 0.75, 0.5]`。

辅助方法（同一个类）：

| 方法 | 作用 | 关键指令 |
| --- | --- | --- |
| `B#c(F)Z` | `0 < x < 50` 判定（enabled 的真正来源） | 0x000a `const/high16 0x42480000` = **50.0f** |
| `B#e(MediaResource;IZ)F` | 从 `MediaResource.e(EDashResource).b(List<DashMediaIndex>)` 里按 indexId 找 `DashMediaIndex.k:String` → `B#d(String)F` 解析 | 0x0044 `iget DashMediaIndex->b:I`；0x0058 `iget ->k:Ljava/lang/String;` |
| `B#d(String)F` | 字符串→float | — |
| `B#b(MediaResource)F` | **另一件事**：读 `ExtraInfo.g:HashMap` 的 `"united_player_script_pace"` → 脚本推荐倍速 | 0x0022 `const-string "united_player_script_pace"` |

> `B#b` 只被长按倍速 / story 长按用到（xref 实证），**与菜单档位表无关**，别混。

### 3.3 元素类：`com.bilibili.playerbizcommonv2.utils.t`（classes18.dex）

Kotlin data class，反编译可读形状：

| 项 | 精确签名 |
| --- | --- |
| 类 | `Lcom/bilibili/playerbizcommonv2/utils/t;`（R8 混淆名，包是真的） |
| 字段 1 | `public final a:F`（倍速值） |
| 字段 2 | `public final b:Z`（enabled：false = 置灰不可用） |
| 构造器 | `public <init>(F Z)V` —— 精确描述符 `(FZ)V` |
| 其它 | `equals` / `hashCode` / `toString`（`"PlaybackSpeedOption(speed=" ... ", enabled=" ... ")"`） |

**`t` 的唯一构造点就是 `B#a`**（xref 实证：全 dex 只有 `B#a` 里 `new-instance .../t;`），
所以「改 `t` 的构造参数」也能改档位表，但**加不了档**（数量由循环次数决定）——见 §5 候选 b。

### 3.4 谁把这张表交给 UI

| # | 调用者（dex） | 传参（实证） | 结果去向 |
| --- | --- | --- | --- |
| 1 | `playerbizcommonv2.widget.speed.e#onWidgetShow()V`（18） | `B.a(media, null, false, 14)` | `speed/f#e0(float, ArrayList)` → RecyclerView 适配器内部 `ArrayList d` |
| 2 | `TheseusSpeedFunctionWidget#f()V`（22） | `B.a(media, Integer, true, 8)` | 同上 `speed/f#e0` |
| 3 | `theseus.united.page.speed.e#b(e)ArrayList`（22，静态中转） | `B.a(media, Integer, true, 8)` | Compose `MutableState<ArrayList>`，由 `speed/c` 的 LaunchedEffect 每次打开重取 |
| 4 | `theseus.united.page.toolbar.MenuService#d(MenuService)Z`（22，静态） | `B.a(media, Integer, true, 8)` | `new kotlin.collections.Z(list)` 包成 `List` → 工具栏下拉菜单，每档一个 item |
| 5 | `playerbizcommonv2.widget.setting.PlayerSettingFunctionWidget2#onWidgetShow()V`（18） | 同形状 | 播放设置面板里的倍速行 |
| 6 | `video.story.action.widget.StoryLandscapeSpeedWidget#i0(StoryPlayer)`（21） | 同形状 | 竖屏故事横屏倍速表 |
| 7 | `video.story.setting.F#f(F)`（21） | 同形状 | 故事设置菜单 |

> 第 4 参 `int` 是 **Kotlin 默认参数位掩码**（`and-int/lit8 v8, 8, 2` / `, 4` 可见），
> `8` = 第 3 参用默认值，`14` = 第 1/2/3 参全用默认值。它不是「模式/类型」枚举，
> 不要试图靠它区分用途。

### 3.5 UI 侧怎么把 `t` 变成可见条目（供判断「要不要额外 hook」）

- **旧 RecyclerView 路径** `speed/f`：
  - `<clinit>` 里还有一份**同值的**静态 `List e`（0x0000~0x0058，与 `B.a` 完全同序），用途未展开（unknown）；
  - `e0(F, ArrayList)`：清空内部 `d`，每项转成 `f$b{a:F, b:Z selected, c:Z enabled}`，
    `selected = |cur - speed| < 0.01`（0x004e `const-wide 0x3F847AE147AE147B`，double 0.01）；
  - `onBindViewHolder`：文案 = `getString(2131899464, String.valueOf(speed))`，
    contentDescription = `getString(2131899692, ...)`，enabled=false 时文字 20% alpha + 追加说明；
  - `onClick`：`tag` 转 `f$b` → `f$a#b(F)` → 设速。**点击不看 enabled**（disabled 项仍可点）。
- **新 Compose 路径**：`theseus.page.speed.a`（XML 视图宿主）`i0()` 里创建 ComposeView /
  `TheseusSpeedFunctionWidget` / `speed.e` 状态对象 / `speed.c` content lambda；
  `speed.e#a(F I I Composer List Function1 Function1)` 渲染时 `iget t.a` / `iget-boolean t.b`；
  点击走 R8 合并 lambda `CB0/a#invoke`（classes17，0x05d8 处 `h.t(F)`）。
- **文案是从数值派生的**（`getString(id, String.valueOf(speed))`），不是每档写死的字符串
  ⇒ 注入新档位后**标题自动正确**，不需要 hook title getter。

---

## 4. 推荐落点与候选评估

### 4.1 排序总表

| 候选 | 形状 | 每次打开重建？ | 会被服务端/实验覆盖？ | 缓存 | 结论 |
| --- | --- | --- | --- | --- | --- |
| **a. `B#a(...)` AFTER 整表替换** | 返回 `ArrayList`（元素 `t`） | **是**，每次 `new ArrayList()` | 否（列表内容全在本地；只有 3.0 档受实验 gate） | 无 | **✅ 首选** |
| b. `t#<init>(F Z)` AFTER 改参数 | ctor，`(FZ)V` | 是 | 否 | 无 | ⚠️ 只能改现有 6 档的值/顺序，**加不了档**；且改的是「值」不是「集合」 |
| c. 替换静态字段 `B.a : List<Float>` | `List<Float>` | 字段只初始化一次 | 否 | 进程级 | ❌ 副作用面太大（见 4.3） |
| d. hook 各调用者（7 处）或适配器 `f#e0` | 各处不同 | 是 | 否 | 有内部副本 | ❌ 只覆盖部分路径，每版都要重找，最脆 |
| e. hook Item title/label getter | 无独立 getter（文案由 `getString(id, speed)` 生成） | — | — | — | ❌ 造不出新档位，只能改字 |
| f. 资源/实验注入 | `LongPressSpeedExperiment` 枚举 + `LongPressSpeedConfig` | Lazy，进程内只算一次 | 是（实验组） | Lazy | ❌ 最多决定「有没有 3.0」，不能自定义集合 |

### 4.2 为什么首选「后处理返回值」

1. **一处覆盖全部 UI 路径**：7 个调用者（旧 RecyclerView 面板、theseus Compose 面板、
   工具栏下拉菜单、播放设置面板、故事面板/菜单）拿的都是这一个返回值。
   分别去 hook 这 7 处 = 每版都要重扫 7 个点，且只要漏一个，功能就「有时生效有时不生效」。
2. **返回类型形状最友好**：`ArrayList` + 元素类只有 `(F Z)` 两个字段。
   用「宿主返回值里 `get(0).getClass()`」拿到元素类，再用
   `getDeclaredConstructor(float.class, boolean.class).newInstance(v, true)` 造条目，
   **一个混淆名都不用写**。
3. **无缓存、无持久化**：方法每次 `new ArrayList()`，面板每次打开重取；
   改完设置**重开面板**即可见，不需要重启播放器，也不存在「旧表还在某处」。
4. **纯后处理、零内部状态污染**：不写宿主字段、不替换适配器、不碰 Compose state，
   最坏情况是「没生效」（返回原列表），不会把宿主改崩。
5. 与模块现有 `setPlaySpeed` 钩子天然兼容：点击新档位 → `h.t(F)` →
   `PlaySpeedManagerImpl` → `eK1.q#setPlaySpeed(F)`，调用栈里有
   `PlaySpeedManagerImpl`，正好命中 `SpeedPolicy.MANUAL_MARKER`，不会被「默认倍速」回弹覆盖。

### 4.3 为什么**不**改静态字段 `B.a`（重要，别被"一行搞定"诱惑）

`B.a : List<Float>` 除了喂菜单，还被 7 处**非菜单**消费者读（xref 实证）：

| 读 `B.a` 的地方（dex） | 用途 |
| --- | --- |
| `com.bilibili.app.gemini.player.widget.speed.GeminiHalfSpeedWidget$b#h(IIZZ)V` / `$bindToView$2$1` | 半屏播放器倍速按钮，按档位**映射图标资源**（`GeminiHalfSpeedWidget#Y` 里 0.5→2131239615、0.75→2131239620 … 6 个写死 drawable） |
| `theseus.miniplayer.ui.c$a#m1(...)`（18） | 小窗长按倍速 |
| `video.story.Z51/c#c(...)`、`C81/r#onClick`、`BusinessCastScreenService$1$2`、`TheseusPlayerQualityService#i(...)` | 故事/投屏/画质联动里的「倍速-画质」判定 |
| `TripleSpeedService$runOldTripleSpeed...`（22，读 `B#b`） | 长按三倍速的兜底值 |

把它们换成用户自定义档位，会出现：半屏播放器找不到对应图标、
「哪个档是 2 倍/3 倍」的硬编码判定（`GeminiHalfSpeedWidget#Y`、`TheseusPlayerQualityService`）
错位、长按倍速行为变化。**菜单只要显示自定义档位，不需要动这些**。
另外它是 `static final`，写 final 字段在各版本 ART 上行为不一致（PITFALLS §54 同源风险）。

### 4.4 推荐实现（与已落地实现一致）

```
hook B#a(...) AFTER:
  result = 宿主原返回值（ArrayList<t>）
  if 用户配置为空 → 原样返回（零干预）
  else:
    cls  = result.get(0).getClass()                  // 元素类，不写名字
    ctor = cls.getDeclaredConstructor(float.class, boolean.class)
    out  = new ArrayList(result.size())
    for (speed : 用户配置, 降序去重, 夹到 0.25..3.0)  out.add(ctor.newInstance(speed, true))
    （可选）若开启了「默认倍速」且该值不在表里，补一条，避免「起播 1.75x 面板里没有 1.75」
    return out
兜底：result 为空/元素类反射失败 → 用候选名 com.bilibili.playerbizcommonv2.utils.t +
      形状校验（(F Z)V 构造器 + 一个 float 字段 + 一个 boolean 字段）后再试；仍失败 → 原样返回 + warn
```

要点：
- 造完的 `ArrayList` 直接作为 `intercept()` 返回值（libxposed 里 `Chain` 没有 `setResult`，
  **hooker 返回非 null 即替换结果**；要放行就 `return chain.proceed()`）。
- 元素 `enabled` 传 `true`。可选：`speed > 3.0f` 传 `false`（UI 会置灰，但**可点**，
  而 native 会钳到 3.0，见 §6）——如果要把「>3 是安慰剂」这件事说清楚，就用 false + 置灰。
- `2.0f` 不必改写成 `1.99f`：宿主自己在 `setPlaySpeed` 里做（§6），面板选中态
  由 `|cur - speed| < 0.01` 判定，注入值与宿主内置值走完全相同的路径。

---

## 5. 需要注入的构造器/字段签名（精确）

| 用途 | 签名 | 备注 |
| --- | --- | --- |
| 造档位条目 | `Lcom/bilibili/playerbizcommonv2/utils/t;-><init>(F Z)V` | `public`，参数 `(float speed, boolean enabled)` |
| 条目速度字段 | `Lcom/bilibili/playerbizcommonv2/utils/t;->a:F` | `public final`，只读；造完就是最终值 |
| 条目可用字段 | `Lcom/bilibili/playerbizcommonv2/utils/t;->b:Z` | `public final` |
| 被 hook 的方法 | `Lcom/bilibili/playerbizcommonv2/utils/B;->a(Lcom/bilibili/lib/media/resource/MediaResource;Ljava/lang/Integer;ZI)Ljava/util/ArrayList;` | `public static` |
| 反查用的静态字段 | `Lcom/bilibili/playerbizcommonv2/utils/B;->a:Ljava/util/List;` | `public static final`，仅作形状校验/日志，**不要写它** |

反射写法（不用写死名字）：

```java
Class<?> itemCls = original.get(0).getClass();              // 正常路径
Constructor<?> ctor = itemCls.getDeclaredConstructor(float.class, boolean.class);
ctor.setAccessible(true);
Object option = ctor.newInstance(speed, Boolean.TRUE);
list.add(option);
```

---

## 6. 3.0x 钳制 与 2.0 → 1.99（6.7.1 复核）

| 结论 | 证据 |
| --- | --- |
| **Java 侧没有 >3.0 的钳制**，任意 float 原样下发给内核 | `eK1.q#setPlaySpeed(F)V`（classes28）0x001a~0x0048 只对 `== 2.0f` 做替换，其余 `move v0, v6` 直通到 0x0060 `OpSwitchSpeed` |
| **仅 `2.0f` → `1.99f`**（0x3FFEB852） | 0x001a `const/high16 v1, 0x40000000`(2.0)、0x001e `cmpg-float`、0x0024 `if-gtz`(>2.0 跳过)、0x003e `if-nez`(≠0 即 <2.0 跳过)、0x0042 `const 1073657938`(0x3FFEB852) |
| 顺带清 `player_key_locked_video_speed` | 0x0028 `const-string "player_key_locked_video_speed"` |
| pref 写入仍在 | 0x0010 `const-string "player_key_video_speed"` → 0x0014 `setting/c.M7(F,String)` |
| **UI 侧反向归一 1.99 → 2.0**（3 处） | `PlayerSpeedWidget#i0()` 0x0012 `const 0x3FFEB852`；`theseus/page/speed/a#l0()` 0x0020 同；`GeminiHalfSpeedWidget#Y()` 0x0012 同 |
| **>3.0 是 placebo** | native `libijkplayer.so#ffp_set_playback_rate` 从 `GetAICenterOutput(9,…)` 读上限并覆盖（PITFALLS §15，本次未重验 native） |
| 3.0 档在**菜单**侧还有一层 gate | `B#a` 里 `LongPressSpeedExperiment ∈ {SmartLongPressAnd3x, Speed2And3x}` + `PlayIndex.b != "downloaded"`；`enabled` 另由 `B#c(F)Z`（`0 < x < 50`）决定 |

实践含义：
- 用户配置里放 `3.0` 是有效的（native 上限就是 3.0），但**绕过了宿主的实验 gate**——
  这正是模块想要的效果，风险是官方 3.0 的「配套服务」（服务端 pace 元数据）可能不存在，未上真机验证。
- 用户配置里放 `>3.0`（如 3.5）**必定无效**（native 覆盖回 3.0），应置灰 + 在设置页提示。
- `2.0` 档点下去实际下发 1.99，宿主自己的按钮文案做了 `1.99→2.0` 归一，
  但面板「选中态」是 `|1.99-2.0| < 0.01` 判定 —— **恰好等于 0.01，严格小于不成立**，
  所以 2.0 档高亮可能不稳定。这是宿主既有行为（内置 2.0 档同样如此），不是注入引入的；
  若要规避，可在注入时把 `2.0` 映射成 `1.99` 并把文案交给宿主（未验证，仅备选）。

---

## 7. 抗漂移

### 7.1 哪些名字可以信任

| 类别 | 符号 | 说明 |
| --- | --- | --- |
| **真名（可依赖）** | `com.bilibili.lib.media.resource.MediaResource` | 第 0 参类型，跨版本稳定 |
| **真名** | `eK1.q#setPlaySpeed(F)V` 里的**方法名** `setPlaySpeed` | Kotlin 接口方法名不混淆（现有 `SpeedHooks` 已按此定位） |
| **真名** | `com.bilibili.player.tangram.basic.PlaySpeedManagerImpl` | 类名未混淆，`SpeedPolicy.MANUAL_MARKER` 依赖它 |
| **真名** | `com.bilibili.playerbizcommonv2.widget.speed.PlayerSpeedWidget`、`TheseusSpeedFunctionWidget`、`toolbar.MenuService`、`LongPressSpeedExperiment` | 类名未混淆，可作**日志/调试锚点**，但**不是档位表的必经点** |
| **混淆名（每版会变）** | `com.bilibili.playerbizcommonv2.utils.B`（构造器类） | 必须形状兜底 |
| **混淆名** | `com.bilibili.playerbizcommonv2.utils.t`（元素类） | 正常路径从返回值反射取，名字只在兜底时用 |
| **混淆名** | `com.bilibili.playerbizcommonv2.widget.speed.f`（适配器）、`theseus...page.speed.a/e/c/d` | 推荐方案**不碰**它们，正是为了不跟着它们漂 |
| **混淆名** | `eK1.q`、`JL1.d`、`LHL1/r` | 与本次落点无关，但换版会一起变 |

### 7.2 形状校验怎么写（可直接抄）

宿主类定位（四级门，全过才 hook）：

```
1) 候选名提示：CANDIDATE_HOLDERS = { "com.bilibili.playerbizcommonv2.utils.B" }
   （6.7.1 实测名；命中只是提示，不代表通过）
2) 在类上找方法：
   Modifier.isStatic(m.getModifiers())
   && m.getName().equals("a")                       // 方法名本身也是混淆名，仅作提示
   && m.getReturnType() == java.util.ArrayList.class
   && m.getParameterCount() == 4
   && m.getParameterTypes()[0] == MediaResource      // 真名类，硬约束
   && m.getParameterTypes()[1] == Integer.class
   && m.getParameterTypes()[2] == boolean.class
   && m.getParameterTypes()[3] == int.class
3) 同类上还存在一个 static List 字段（6.7.1 是 B.a）：
   任一 field：Modifier.isStatic && List.class.isAssignableFrom(type)
   —— 这条把「恰好同形状的别的工具方法」排除掉
4) 元素类形状（兜底路径才用）：
   ctor (float, boolean)；有一个 float 字段 + 一个 boolean 字段；
   toString() 以 "PlaybackSpeedOption(" 开头（字符串实证，可作为最后一道确认）
```

失败行为（与 `PITFALLS §35/§40` 一致）：**只打一条 warn + 零干预**，
把试过的候选名与「期望形状」打进日志，让人改一行常量即可修锚，**不要**猜着改别人的列表。

> 若想减少「换版后连候选名都猜不到」的痛点，可以加一个**全字母补扫**：
> `Class.forName("com.bilibili.playerbizcommonv2.utils." + ch)`，`ch` 遍历 `A-Z a-z`（52 次），
> 每个命中都过上面 2)3) 两道形状门。包名是固定的、R8 单字母名是稠密的，
> 这个补扫成本远低于枚举 34 个 dex 的 ClassLoader（且 libxposed 不提供类表）。

### 7.3 换版重新校锚的流程（下次照做）

1. `fastdex.py index` 建索引；
2. `01_scan_speed_consts.py` 全 dex 扫浮点常量，按「非 1.0 常量个数」排序 →
   找 `<clinit>` / `ArrayList` 返回方法里同时出现 0.5/0.75/1.25/1.5/2.0 的那个类；
3. `04_smali.py` 确认「静态 List 字段 + 4 参静态方法」；
4. `05_xref.py`/`06_xref_narrow.py` 确认调用者仍是「面板 + 菜单」那几处；
5. 更新 `CANDIDATE_HOLDERS` / `CANDIDATE_ITEMS` 与本文档 §2 的 dex 文件名。

---

## 8. 风险与未知（明确标注）

**未上真机验证**：本文所有结论都是静态的（dex 指令 + 字符串 + 资源 id），
没有一次真实点击/播放。以下条目属于「需要在真机上确认」：

1. **9 档在真机面板里是否放得下**（Compose 面板高度/滚动、RecyclerView 横排是否需要滚动）；
2. **>3.0 档的实际表现**：native 覆盖回 3.0 是 PITFALLS §15 的旧结论，6.7.1 的
   `libijkplayer.so` 未重验；菜单层置灰是否能正确表达这件事（UI 置灰但仍可点，见 §3.5）；
3. **2.0 档选中态**：`|1.99 - 2.0| < 0.01` 严格小于不成立，内置档同样受影响，
   注入后是否更明显未验证（§6）；
4. **3.0 档绕过实验 gate 后是否有服务端配套**（官方 3.0 可能要求服务端 pace 元数据）；
5. **`speed/f.<clinit>` 里那份同值静态 `List e`（classes18）用途未展开**——
   如果它在某条路径上被当作「合法档位白名单」用来校验，注入 >2.0 的值可能被它挡掉（未知，未做 xref）；
6. **故事/竖屏与播放设置面板**：xref 只证明它们调 `B.a`，**未逐条确认**注入后它们的布局/文案是否也正常
   （它们和主面板共用一张表，风险主要来自 UI 而不是数据）；
7. `LongPressSpeedExperiment` 的配置来源：`B` 通过 `com.bilibili.playerbizcommonv2.utils.g#j`
   （`kotlin.Lazy`，`<clinit>` 0x0170 `sput-object ... ->j`，由 `AD1/c#<init>(I=15)` 惰性计算）拿到
   `LongPressSpeedConfig(group=...)`，**具体从哪个 DD/服务端 key 读，未解出**（`AD1/c` 是 R8 合并的
   lambda 分发器，case 15 分支未逐指令拆）；因为是 Lazy，**进程内只会算一次**，
   所以「会话中途被服务端改档位」这条路基本不存在；
8. `B.a` 的 4 参调用里第 4 参是 Kotlin 默认参数位掩码（`8`/`14`），
   本模块 hook 不依赖它，但若将来宿主改用「非默认值」传参（例如显式传 idx 类型），
   形状校验仍然通过、语义可能变化——未验证。

---

### 8.1 后续补证：`speed/f.<clinit>` 那份同值静态 List 是死字段（本次交付时复扫全部 34 个 dex）

用 androguard 对 `classes*.dex` 全量扫 `sget/sput/iget/iput` 中引用
`Lcom/bilibili/playerbizcommonv2/widget/speed/f;->e:Ljava/util/List;` 的指令：
**只有 `f.<clinit>` 里一条 `sput-object` 写它，全包没有任何 `sget` 读它**。
所以它不是「另一条会绕过 `B#a` 的档位来源」，而是一个没被使用的静态表（大概率是重构残留）。
结论不变：**只挂 `B#a` 的返回值即可覆盖全部菜单消费路径**；这条未知可以从风险清单里划掉。

## 9. 附：本次分析的脚本清单

| 文件 | 作用 |
| --- | --- |
| `/root/work/recon/reconlib.py` | androguard 薄封装、f32 位型表、逐 dex 释放 |
| `fastdex.py` | 极简 DEX header/索引解析（8 秒建 283,876 类索引），`index`/`cls`/`str` 子命令 |
| `01_scan_speed_consts.py` | 全 dex 扫倍速浮点常量 → `out/speed_const_hits.json` |
| `03_dump_class.py` | 按类名/正则 dump 方法签名+常量+字符串+调用 |
| `04_smali.py` | 精确 smali（带 16 位码元偏移），本文所有偏移都由它产出 |
| `05_xref.py` | 全 dex xref（方法/字段/字符串），支持批量查询文件 |
| `06_xref_narrow.py` | 先用 method_id 表窄化到少数 dex 再扫，秒级 |
| `07_find_field_readers.py` | 查「谁读某个字段」 |
| `09_show.py` + `args.txt` | 绕开 shell 对 `$` 的展开，dump 内部类 |
| `10_arsc.py` | `resources.arsc` 字符串查询（本次用 androguard 4.1.4 的 API 未打通，未产出结论） |

> 纪律说明：全程**只读**宿主编译产物，未修改 APK/dex；未改 `/root/src/BiliTamer/app/` 下任何源码；
> 本文件是唯一新增的文档。
