# 6.7.1 倍速锚点实证记录

> 这份文件是**证据留档**，不是设计文档。结论与取舍写在 PITFALLS §60；
> 这里保留复算所需的原始证据（类名、行号、smali 片段、命令），
> 方便下一版宿主升级后按同样方法重新校锚点。

复算命令（宿主机需 JDK + 网络）：

```bash
# 1) 解包
unzip -o app.xapk -d xapk && unzip -o xapk/com.bilibili.app.in.apk -d base 'classes*.dex'
# 2) 反编译单类（jadx 1.5.0）
./jadx/bin/jadx --no-res --single-class eK1.q -d out xapk/com.bilibili.app.in.apk
# 3) 反汇编（baksmali 2.5.2，Maven 依赖见 https://repo1.maven.org/maven2/org/smali/）
java -cp <dexlib2:smali:baksmali:guava:util:jcommander> org.jf.baksmali.Main d base/classes28.dex -o smali28
# 4) 找锚点：哪个类声明了 setPlaySpeed(float)
grep -rn "\.method public final setPlaySpeed(F)V" smali28/
```


来源：APKPure xapk → com.bilibili.app.in.apk（349 MB，34 个 dex）→ jadx 1.5.0 + baksmali 2.5.2。
所有类定义都在 **classes28.dex**（已用 class_defs 严格核对，不是字符串命中）。

## 真实链路（从下往上）

| 层 | 符号 | 说明 |
| --- | --- | --- |
| 内核 | `tv.danmaku.ijk.media.player.IjkMediaPlayer#setSpeed(float)` (L3313) | `doAsyncTask(msg 16, 10003, Float)` → native。**public，可 hook** |
| 适配器 | `JL1.d#e(Ops, Object)` → `:pswitch_67d` 分支 | 判 `instanceof Float` → `IjkMediaPlayer.setSpeed(F)`（L5133） |
| 播放器 | `eK1.q#setPlaySpeed(float)` (L18251) | 写 pref `player_key_video_speed`（`setting/c.M7(F,String)`）→ `Ops.OpSwitchSpeed` → 适配器；末尾 `"[player] player speed type="` 日志 |
| 起播 | `eK1.f#onPrepared(IMediaPlayer)` (L96 附近) | `q.R0(false)` 算初值 → `q.setPlaySpeed(v)` |
| 初值 | `eK1.q#R0(boolean)` (L9641) | **`const/high16 v0, 0x3f800000 # 1.0f`** 是默认返回；true 分支读 `IjkMediaPlayer.getSpeed(1.0f)`，false 分支读 pref |

## 为什么 biliSpeed 那套在本版完全无效（三个独立原因）

1. **`resume()` 不存在**：`IjkMediaPlayer` 只有 `start()`（L3372）和 `pause()`（L2907）。
   biliSpeed 的「起播重设」挂在 `s0.resume()` 上，**这个锚点在本版根本挂不上**，
   `findAndHookMethod` 会抛 `NoSuchMethodError`，整条链在 L5 之后静默失败。
2. **`notifyOnInfo` 不是起播入口**：它是 `AbstractMediaPlayer` 的通用事件分发（info 回调），
   起播走的是 `eK1.f.onPrepared`。挂在 notifyOnInfo 上只能碰运气，且拿到的监听器持有链
   在本版已重构（`JL1.d` 里没有 `declaredFields[0] → OnPreparedListener` 那条链）。
3. **速度真值在上层**：即使把 `IjkMediaPlayer.setSpeed(1.5)` 设进去，
   `eK1.f.onPrepared` 每次起播都会调 `q.R0(false)` 从 pref 重算（默认 1.0f）并 `setPlaySpeed`，
   把内核值覆盖回 1.0f。**必须改上层，只改内核等于白改。**

## 正确的落点（v1.10.2 修订）

> 下面这段是 v1.10.1 的原始结论，**v1.10.2 已改**：删掉了 R0 兜底与「手动跟随」两段。
> 现在的实现只有 `setPlaySpeed` 一个落点，且**只在起播（栈里有 `onPrepared`）时替换入参，
> 其余时刻一律原样放行**。原因见 PITFALLS §63——「用户手动调速」用栈里的类名判不出来，
> 而「不拦」本身就是跟随。这里保留旧文字是为了让下一版知道当初为什么是错的。

* **唯一落点**：`eK1.q#setPlaySpeed(float)` **before**，当本次调用来自起播
  （`onPrepared` 帧）时把入参替换为配置值；非起播原样放行。
  理由：它同时喂 pref 写入与 `OpSwitchSwitch`，改这里 = 上层与内核一致，不会被 R0 反弹。
* ~~兜底落点：`eK1.q#R0(boolean)` **after**，返回 1.0f 时改成配置值。~~
  已删除：R0 的结果只会被 `onPrepared` 立刻喂给 `setPlaySpeed`，没有绕过路径；
  多挂一处只会多一个把用户选档改回去的改写面（审计 §11.1）。
* ~~手动跟随：栈里有 `PlaySpeedManagerImpl` → 判定为手动。~~
  已删除：该类不在 6.7.1 两条真实手动链路的栈上（PITFALLS §63）。
* `IjkMediaPlayer.setSpeed` **不要**改（内核层，改了会被上层覆盖，且可能破坏 ABR/时钟）。

## 抗漂移

* `IjkMediaPlayer#setSpeed(float)`、`AbstractMediaPlayer#notifyOnInfo` 是宿主真名（跨版本稳定）。
* `eK1.q` / `eK1.f` / `JL1.d` 是 R8 混淆名，**必须按形状兜底**：
  - `setPlaySpeed(F)V` 是**真名**（Kotlin 接口方法名不混淆），类名会漂 → 按「含 setPlaySpeed(F)V 的类」找。
  - 起播判据用 `onPrepared`（接口实现方法名，跨版本稳定），不要用 `eK1.f` 这个混淆类名。
  - ~~`R0(Z)F` 按方法体特征认~~ —— v1.10.2 已不需要（L3 兜底删除）。
