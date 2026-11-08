package com.tamer.bili.hooks;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 播放器默认倍速（移植自 biliSpeed 的 B 站段，落点按 6.7.1 dex 实证重写）。
 *
 * <b>为什么不能用 biliSpeed 原版的锚点（6.7.1 / versionCode 9161100 反编译实证）：</b>
 * 原版挂在 {@code AbstractMediaPlayer#notifyOnInfo} → {@code mOnPreparedListener} 持有链 →
 * 某个对象的 {@code resume()} 上。这套在本版有三个独立的死因：
 * <ol>
 *  <li><b>没有 {@code resume()}</b>：本版 {@code IjkMediaPlayer} 只有 {@code start()} 与
 *      {@code pause()}，原版的「起播重设」锚点根本不存在，挂钩直接抛异常，整条链静默失败；</li>
 *  <li><b>{@code notifyOnInfo} 不是起播入口</b>：它是 {@code AbstractMediaPlayer} 的通用 info
 *      事件分发；起播走的是播放器核心自己的 {@code onPrepared}；</li>
 *  <li><b>速度真值在上层</b>：内核 {@code IjkMediaPlayer.setSpeed(float)} 只把值塞进异步消息队列
 *      （msg 16 / what 10003 → native）。而每次起播，上层都会从 pref
 *      {@code player_key_video_speed} 重算一个初值并<b>再设一遍</b>，把内核值覆盖回 1.0f。
 *      只改内核 = 白改。</li>
 * </ol>
 *
 * <b>本版真实链路（classes28.dex，jadx + baksmali 逐条核过）：</b>
 * <pre>
 *   eK1.f#onPrepared(IMediaPlayer)        ← 起播
 *     └→ eK1.q#R0(boolean)                ← 算初值；默认返回常量 1.0f，否则读 pref / 读内核
 *     └→ eK1.q#setPlaySpeed(float)        ← 唯一的「设置速度」入口
 *          ├→ setting/c.M7(float, "player_key_video_speed")   写 pref
 *          └→ adapter.e(Ops.OpSwitchSpeed, Float)
 *               └→ JL1.d#e(...) :pswitch 分支 → IjkMediaPlayer#setSpeed(float) → native
 * </pre>
 *
 * <b>落点：改 {@code setPlaySpeed} 的入参，不改内核。</b>这个入参在方法体内被用在三处：
 * 写 pref {@code player_key_video_speed}、经 {@code OpSwitchSpeed} 下发内核、以及最后那句
 * {@code "[player] player speed type="} 日志。替换入参 = 三处同时一致；只改返回值会让 pref
 * 与内核记着旧值，下一轮 {@code R0} 读 pref 时就对不上。
 * 顺带记一条宿主细节：该方法的参数等于 {@code 2.0f} 时会被它自己改成 {@code 1.99f} 下发
 * （避开正好 2.0 的某个判定），所以设 2.0 时内核拿到的是 1.99——这是宿主行为，不是我们的 bug。
 *
 * <b>只在「起播」这一个事件上改，其余时刻零干预（v1.10.2 修正，PITFALLS §63）：</b>
 * v1.10.1 曾想区分「用户手动调速」并跟随用户，判据是调用栈里有没有
 * {@code PlaySpeedManagerImpl}。6.7.1 dex 实证这个类根本不在两条真实手动链路的栈上
 * （旧面板 {@code playerbizcommonv2.widget.speed.e} 直接调 {@code A.setPlaySpeed}；
 * Theseus 面板经 {@code theseus.keel.player.h#t(F)} 写 StateFlow 再由 flow 收集器回调），
 * 于是「手动」永远判 false、用户的选档被当成「宿主复位」改回配置值——承诺的跟随变成静默拦截。
 * 现在不再识别手动：<b>非起播一律原样放行</b>，于是用户选档天然立即生效（我们不拦），
 * 下一次起播又被配置值覆盖（不记忆）。也不再需要会话状态机与跨线程共享的手动值。
 *
 * <b>抗漂移（PITFALLS §16/#54：类名只当候选，真名与形状才是锚）：</b>
 * {@code eK1.q} 是 R8 混淆名，每版会换；但 {@code setPlaySpeed(float)} 是 Kotlin 里的
 * <b>真名</b>（接口方法不混淆），所以这里<b>不写死类名</b>：类名只作提示，
 * 真正的判定是「该类上存在 {@code public void setPlaySpeed(float)}」。
 * 提示未命中时<b>不猜</b>：不枚举 ClassLoader（34 个 dex、单字母混淆包，libxposed 也不给类表），
 * 不挂类加载钩子（播放器核心在启动早期就加载完了）。直接 warn 并指出「去新包里找谁声明了
 * {@code setPlaySpeed(float)}，把它加进候选表」——可维护路径比一个假的自动发现诚实。
 *
 * <b>上限为什么是 3.0：</b>native {@code libijkplayer.so} 的 {@code ffp_set_playback_rate}
 * 会经 {@code GetAICenterOutput(9,…)} 读速率上限，超限即覆盖并打 {@code adjust_playback_rate}
 * （PITFALLS §15）。模块不承诺 >3.0，配置侧也够不到。
 *
 * <b>日志口径（PITFALLS §32/§41）：</b>「钩子挂上了」与「速度真的改了」分开打，
 * 每类首条必出（verbose 关也出）：{@code anchor L1 hit} / {@code armed} / {@code applied}；
 * 首次遇到非起播调用时额外打一条「调用者是谁」（debug），给下一版校锚点留读数。
 */
public final class SpeedHooks {

    /** 上层「设置速度」入口的真名（Kotlin 方法名不混淆，跨版本稳定）。 */
    private static final String M_SET_PLAY_SPEED = "setPlaySpeed";

    /**
     * 候选类名：6.7.1 实测 {@code eK1.q}。写在这里只作**提示**，真正的定位是
     * 「谁声明了 setPlaySpeed(float)」，所以宿主换版改了混淆名也能自己找回来。
     * 提示命中可以省一次全量扫描。
     */
    private static final String[] CANDIDATE_HOLDERS = {
            "eK1.q",
    };

    private final HookApi api;
    private final ClassLoader cl;

    private final AtomicBoolean armedLogged = new AtomicBoolean(false);
    private final AtomicBoolean appliedLogged = new AtomicBoolean(false);
    private final AtomicBoolean passLogged = new AtomicBoolean(false);
    /** 非起播调用计数：用来发现「起播判据失效（onPrepared 帧从未出现）」这种静默不生效。 */
    private final AtomicInteger passCount = new AtomicInteger(0);
    private final AtomicBoolean installed = new AtomicBoolean(false);

    public SpeedHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        if (!installed.compareAndSet(false, true)) {
            api.debug("speed: already installed, skip");
            return;
        }
        // ===== L1：定位「声明了 setPlaySpeed(float) 的类」 =====
        Class<?> holder = null;
        Method setPlaySpeed = null;
        for (String cand : CANDIDATE_HOLDERS) {
            Class<?> c = HookUtil.cls(cl, cand);
            if (c == null) {
                continue;
            }
            Method m = findMethod(c, M_SET_PLAY_SPEED, float.class, void.class);
            if (m != null) {
                holder = c;
                setPlaySpeed = m;
                api.info("speed: anchor L1 hit hint " + cand + "#" + M_SET_PLAY_SPEED);
                break;
            }
        }
        if (holder == null) {
            api.warn("speed: anchor L1 miss. Tried " + java.util.Arrays.toString(CANDIDATE_HOLDERS)
                    + " for public void " + M_SET_PLAY_SPEED + "(float)."
                    + " Re-anchor by finding the class that declares " + M_SET_PLAY_SPEED
                    + "(float) in the new build (see PITFALLS §60) and add it to CANDIDATE_HOLDERS");
            return;
        }

        // ===== L2：hook setPlaySpeed 的入参（唯一落点） =====
        try {
            api.deoptimize(setPlaySpeed);
            api.addHook("speed: " + holder.getName() + "#" + M_SET_PLAY_SPEED,
                    setPlaySpeed, new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object arg = chain.getArg(0);
                            if (!(arg instanceof Number)) {
                                return chain.proceed();
                            }
                            float requested = ((Number) arg).floatValue();
                            // 开关关着就零干预：连调用栈都不抓（这个 hook 在每次设速时都会进，
                            // 抓栈是这里唯一有成本的动作）。配置每次现读，不缓存。
                            if (!api.isPlaybackSpeedEnabled()) {
                                return chain.proceed();
                            }
                            // 只在起播时动参数；非起播（用户手动选档、宿主自己复位）一律放行。
                            // 起播判据是栈里有 onPrepared（接口方法名，不混淆；
                            // dex 实证 eK1.f#onPrepared 无条件调用 eK1.q#setPlaySpeed）。
                            boolean startup = isStartupCall(Thread.currentThread().getStackTrace());
                            float want = requested;
                            try {
                                want = decide(requested, startup, Thread.currentThread());
                            } catch (Throwable t) {
                                // 决策出错一律原样放行：宁可不生效，也不许改坏宿主的速度。
                                api.debug("speed: L2 decide threw (pass through): " + t);
                                want = requested;
                            }
                            if (Float.compare(want, requested) == 0) {
                                return chain.proceed();
                            }
                            return chain.proceed(new Object[]{Float.valueOf(want)});
                        }
                    });
            if (armedLogged.compareAndSet(false, true)) {
                api.info("speed: armed on " + holder.getName() + "#" + M_SET_PLAY_SPEED
                        + "(float) (stage=L2)");
            }
        } catch (Throwable t) {
            api.warn("speed: anchor L2 hook failed: " + t);
        }
    }

    /**
     * 决定这次应该用什么速度。
     *
     * @param requested 宿主这次想设的值
     * @param startup   本次调用是否由起播（{@code onPrepared}）触发
     * @return 实际要用的值；不需要动时原样返回 {@code requested}
     */
    private float decide(float requested, boolean startup, Thread owner) {
        if (!startup) {
            // 非起播：用户手动选档 / 宿主自己复位。一律放行——「跟随用户」就是「不拦」，
            // 「不记忆」由下一次起播覆盖。v1.10.1 在这里改回配置值，等于把用户的选档吃掉。
            // 首条用 info（不受 verbose 影响）：真机上「用户选档没被拦」这件事要能在
            // 设置页导出的日志里直接看到，而不是先开详细日志。
            if (passLogged.compareAndSet(false, true)) {
                api.info("speed: pass through non-startup call " + fmt(requested)
                        + " from " + callerOf(owner) + " (零干预; v1.10.2 语义)");
            } else {
                api.debug("speed: pass through non-startup call " + fmt(requested));
            }
            // 静默不生效的告警：如果设速调用进来了很多次、却一次都没见到 onPrepared 帧，
            // 说明起播判据在这版宿主上失效（换播放器/换起播路径）。没有这条告警时，
            // 用户只会看到「开关开了没效果」，而日志里全是正常的 pass through。
            if (passCount.incrementAndGet() == 30 && !appliedLogged.get()) {
                api.warn("speed: 30 non-startup setPlaySpeed calls but never applied once —"
                        + " the onPrepared frame was never seen; default speed may be inert"
                        + " on this build (see PITFALLS §60/§63)");
            }
            return requested;
        }
        float want = SpeedPolicy.startupSpeed(true, api.getPlaybackSpeedPct());
        if (Float.compare(want, requested) != 0) {
            if (appliedLogged.compareAndSet(false, true)) {
                api.info("speed: applied " + fmt(want) + " at start (was " + fmt(requested) + ")");
            } else {
                api.debug("speed: applied " + fmt(want) + " at start (was " + fmt(requested) + ")");
            }
            return want;
        }
        return requested;
    }

    /**
     * 这次 setPlaySpeed 是不是「起播」触发的。
     *
     * 判据：调用栈里出现 {@code onPrepared}（宿主起播回调的固定方法名，接口方法不混淆）。
     * 不用类名——{@code eK1.f} 是混淆名，换版会变；{@code onPrepared} 是实现
     * {@code IMediaPlayer.OnPreparedListener} 的方法名，跨版本稳定。
     */
    private static boolean isStartupCall(StackTraceElement[] frames) {
        for (StackTraceElement f : frames) {
            if ("onPrepared".equals(f.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    /** 日志用：取第一个宿主调用帧（跳过 hook 自己的包装栈），便于真机排查「谁在调」。 */
    private static String callerOf(Thread owner) {
        try {
            StackTraceElement[] frames = owner.getStackTrace();
            for (StackTraceElement f : frames) {
                String cn = f.getClassName();
                if (cn == null) {
                    continue;
                }
                if (cn.startsWith("io.github.libxposed") || cn.startsWith("com.tamer.bili")
                        || cn.startsWith("java.lang.Thread") || cn.startsWith("android.os")) {
                    continue;
                }
                return cn + "#" + f.getMethodName();
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /** 在该类（及其父类）上找「名字 + 单参类型 + 返回类型」都对上的方法。 */
    private static Method findMethod(Class<?> c, String name, Class<?> param, Class<?> ret) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals(name) && p.length == 1 && p[0] == param
                        && m.getReturnType() == ret) {
                    try {
                        m.setAccessible(true);
                    } catch (Throwable ignored) {
                    }
                    return m;
                }
            }
        }
        return null;
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.US, "%.2f", Float.valueOf(v));
    }
}
