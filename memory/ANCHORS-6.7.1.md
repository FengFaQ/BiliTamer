# 6.7.1 锚点速查 / Anchor cheat-sheet

> 宿主：哔哩哔哩国际版 `com.bilibili.app.in`，versionCode **9161100**（APKPure 6.7.1，34 个 dex）。
> 详细证据与复算命令见 [`docs/speed-menu-anchor-6.7.1.md`](../docs/speed-menu-anchor-6.7.1.md)、
> [`docs/speed-anchor-6.7.1.md`](../docs/speed-anchor-6.7.1.md)、[`docs/speed-default-audit.md`](../docs/speed-default-audit.md)。

## 一、默认倍速（起播套用）

| 角色 | 符号 | dex | 备注 |
| --- | --- | --- | --- |
| 设速入口（**唯一落点**） | `eK1.q#setPlaySpeed(F)V` | classes28 | 方法名是真名（Kotlin 接口），类名是 R8 混淆名 |
| 入参用处（三处） | 写 pref `player_key_video_speed`、`OpSwitchSpeed` 下发内核、日志 | classes28 | 替换入参 = 三处一致 |
| 起播判据 | 调用栈里出现 `onPrepared` | — | 宿主侧 `eK1.f#onPrepared(IMediaPlayer)` 无条件调用 `setPlaySpeed` |
| 初值计算（**不再 hook**） | `eK1.q#R0(Z)F` | classes28 | 结果只会被 onPrepared 喂给 setPlaySpeed，无绕过路径 |
| 宿主细节 | 参数 `== 2.0f` → 自己改成 `1.99f` 下发 | classes28 | 所以设 2.0 时内核收到 1.99 |
| 内核 | `IjkMediaPlayer#setSpeed(F)V`（真名） | — | **不要** hook（会被上层覆盖） |
| 上限 | native `libijkplayer.so#ffp_set_playback_rate` 3.0x 硬钳制 | — | `PITFALLS.md` §15 |

## 二、更多倍速档位（菜单）

| 角色 | 符号 | dex | 备注 |
| --- | --- | --- | --- |
| 档位表构造（**hook 点**） | `com.bilibili.playerbizcommonv2.utils.B#a(Lcom/bilibili/lib/media/resource/MediaResource;Ljava/lang/Integer;ZI)Ljava/util/ArrayList;` | classes18 | `public static`，每次 `new ArrayList()`，无缓存 |
| 静态档位表 | `B.a:Ljava/util/List;` | classes18 | `<clinit>` = `listOf(2.0f,1.5f,1.25f,1.0f,0.75f,0.5f)`；**不要写它**（final + 7 处非菜单消费者） |
| 3.0 档 gate | `LongPressSpeedExperiment ∈ {SmartLongPressAnd3x, Speed2And3x}` 且 `PlayIndex.b != "downloaded"`；`enabled` 由 `B.c(F)Z`（`0<x<50`） | classes18 | 注入条目直接 `enabled=true` ⇒ 有意绕过 gate |
| 元素类 | `com.bilibili.playerbizcommonv2.utils.t`：字段 `a:F`（速度）、`b:Z`（可点）；构造器 `(FZ)V` | classes18 | 正常从宿主返回值 `get(0).getClass()` 反射取 |
| 调用点（≥5） | `playerbizcommonv2.widget.speed.e#onWidgetShow()`（旧面板，`(media,null,false,14)`）；`ship.theseus.united.page.speed.TheseusSpeedFunctionWidget#f()`（theseus，`(media,Integer,true,8)`）；`ship.theseus.united.page.speed.e#b(...)`（静态中转→Compose MutableState）；`ship.theseus.united.page.toolbar.MenuService#d`；播放设置面板 | classes18/22 | 都走同一个 `B#a`，所以一处 hook 全覆盖 |
| 死字段 | `playerbizcommonv2.widget.speed.f.e:Ljava/util/List;` | classes18 | 全包只有 `<clinit>` 写、**没有任何读取**，不是绕过路径 |

## 三、抗漂移要点（换版重新校锚时看这里）

1. **只信真名与形状**：`setPlaySpeed` / `onPrepared` / `IMediaPlayer` 是真名；
   `eK1.q`、`B`、`t` 是 R8 混淆名，每版都换。
2. 定位宿主类用**形状**：`B` 的判据是「同类有静态 `List` 字段 + 静态
   `(MediaResource, Integer, boolean, int) → ArrayList` 方法」；`eK1.q` 的判据是
   「声明 `public void setPlaySpeed(float)`」。
3. 元素类**从运行期返回值取**：`hostList.get(0).getClass()` + `(float, boolean)` 构造器。
4. 找不到就 **warn + 零干预**，绝不猜着改（`PITFALLS.md` §40/§54/§63）。
5. 复算命令（WSL，装 `androguard` 即可）：

```bash
python3 - <<'PY'
from androguard.core.dex import DEX   # pip install androguard
import glob
for f in sorted(glob.glob('base/classes*.dex')):
    d = DEX(open(f, 'rb').read())
    for c in d.get_classes():
        for m in c.get_methods():
            if m.get_name() == 'setPlaySpeed' and m.get_descriptor() == '(F)V':
                print(f, c.get_name(), m.get_name(), m.get_descriptor())
PY
# 菜单锚点：找「静态 List 字段 + 静态 (MediaResource,Integer,boolean,int)ArrayList」
```
