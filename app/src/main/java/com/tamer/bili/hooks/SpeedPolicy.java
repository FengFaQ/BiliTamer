package com.tamer.bili.hooks;

/**
 * 倍速决策的纯逻辑（无 android 依赖、无反射、无静态可变状态）。
 *
 * 为什么单独抽一个类：这套判定是「起播取值 / 越界 clamp / 百分比换算」的唯一出处，
 * 出错的表现是「倍速悄悄不对」——不会崩、不会报错，只能靠对照实验才能看出来。
 * 把它做成纯函数，就能在纯 JVM 上一条命令跑完所有边界（tools/speedpolicy_test.py），
 * 不必为了验一条 clamp 去装一台真机。（PITFALLS §41：读数口径要能被独立复算。）
 *
 * <b>语义（v1.10.2 起）：只在「起播」这一个事件上把速度改成配置值，其余时刻零干预。</b>
 * 也就是：
 * <ul>
 *  <li>起播（{@code onPrepared} 调用 {@code setPlaySpeed}）→ 用配置值；</li>
 *  <li>用户在面板里选一档 → 宿主带着用户的值调 {@code setPlaySpeed}，<b>我们原样放行</b>，
 *      所以「立即跟随」；下一次起播又被配置值覆盖，所以「不记忆」；</li>
 *  <li>宿主自己复位（缓冲结束、切清晰度等）→ 原样放行，不抢方向盘。</li>
 * </ul>
 *
 * <b>为什么不识别「这是用户手动调速」（v1.10.1 的做法，已废弃）：</b>旧实现靠调用栈里有没有
 * {@code com.bilibili.player.tangram.basic.PlaySpeedManagerImpl} 来判手动。6.7.1 dex 实证这个类
 * 根本不在两条真实手动链路的栈上——旧面板是 {@code playerbizcommonv2.widget.speed.e} 直接调
 * {@code A.setPlaySpeed}；Theseus 面板是 {@code theseus.keel.player.h#t(F)} 写 StateFlow、
 * 再由 flow 收集器 {@code player.tangram.playercore.k} 回调。于是「手动」永远判 false，
 * 用户的选档被当成「宿主复位」改回配置值——**承诺的跟随变成了静默拦截**（PITFALLS §63）。
 *
 * 修法不是再加几个类名（那是猜），而是<b>把「非起播」从「改回配置」改成「不干预」</b>：
 * 不再需要识别手动、不再需要会话状态、也不再需要跨线程共享的手动值。
 * 「起播」的判据是栈里有 {@code onPrepared}（接口方法名，不混淆；dex 实证
 * {@code eK1.f#onPrepared} 无条件调用 {@code eK1.q#setPlaySpeed}）。
 *
 * 约定：本类**不许**持有任何可变静态字段；配置每次由调用方实时传入
 * （设置页是投递热生效的，缓存成 install 时的快照会让「改了不生效」重演）。
 */
public final class SpeedPolicy {

    /** 倍速下限（宿主原生菜单的最低档）。 */
    public static final float MIN = 0.25f;
    /** 倍速上限：与 native libijkplayer 的 3.0x 硬钳制对齐（PITFALLS §15）。 */
    public static final float MAX = 3.0f;
    /** 「不干预」的参照值（也是非法输入的回落值）。 */
    public static final float NORMAL = 1.0f;

    /**
     * 配置键的**写入侧**范围（整数百分比 25..300 = 0.25x..3.0x）。
     * 设置页输入校验与出厂默认值都按这个来：写进去的值本来就只可能落在行为区间里。
     */
    public static final int PCT_WRITE_MIN = 25;
    public static final int PCT_WRITE_MAX = 300;

    /**
     * 配置键的**读取侧**兜底范围（25..400）。比写入侧宽，只为兼容手写 conf / 旧副本：
     * 超出写入侧但在读取侧内的值不报错、不迁移，直接由 {@link #fromPct(int)} 夹到 3.0f。
     * 宽出来的余量不会让它「生效成 4.0x」——行为区间始终是 0.25..3.0。
     */
    public static final int PCT_MIN = 25;
    public static final int PCT_MAX = 400;

    private SpeedPolicy() {
    }

    /**
     * 夹到 [0.25, 3.0]。
     * NaN/±inf 一律给 1.0f：这类值只可能来自配置解析或宿主异常传参，
     * 送进播放器会变成「无声/卡帧」而不是报错，静默坏掉最难查，所以就地止损。
     */
    public static float clamp(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) {
            return NORMAL;
        }
        if (v < MIN) {
            return MIN;
        }
        if (v > MAX) {
            return MAX;
        }
        return v;
    }

    /**
     * 起播时应设的值：开关关着返回 {@link #NORMAL}（调用方据此判断「不干预」），
     * 开着返回配置值（已夹到行为区间）。
     *
     * 之所以把「开关」也放进纯函数：它是这条规则的一部分，留在 hook 里就会出现
     * 「一处判断开关、另一处不判断」的分叉（v1.10.1 的 clamp 日志分叉就是这么来的）。
     */
    public static float startupSpeed(boolean enabled, int pct) {
        if (!enabled) {
            return NORMAL;
        }
        return clamp(fromPct(pct));
    }

    /** 浮点倍速 → conf 里的整数百分比（四舍五入：1.5f → 150，1.234f → 123）。 */
    public static int toPct(float speed) {
        if (Float.isNaN(speed) || Float.isInfinite(speed)) {
            return Math.round(NORMAL * 100f);
        }
        // 先夹再换算：越界值四舍五入后会被存进配置，进而在界面上显示成一个
        // 用户从没选过的数字。夹的是行为区间（0.25..3.0），其百分比必然落在写入侧 25..300 内。
        return Math.round(clamp(speed) * 100f);
    }

    /**
     * conf 里的整数百分比 → 浮点倍速。
     *
     * 百分比是**存储格式**（写入侧 25..300，读取侧兜底 25..400），clamp 是**行为边界**（0.25..3.0）。
     * 读取侧比写入侧宽，是为了让手写的 conf（或旧副本）不至于被整行丢掉，而是被夹到边界：
     * 所以 fromPct(400) 得到的是 3.0f，不是 4.0f。
     */
    public static float fromPct(int pct) {
        int p = pct;
        if (p < PCT_MIN) {
            p = PCT_MIN;
        } else if (p > PCT_MAX) {
            p = PCT_MAX;
        }
        return clamp(p / 100f);
    }
}
