package com.tamer.bili.hooks;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 「自定义倍速档位列表」的纯逻辑（无 android 依赖、无反射、无静态可变状态）。
 *
 * 为什么单独抽一个类：这一段的错误形态是「菜单里多/少/错了一个档位、或者顺序怪」——
 * 不崩、不报错，只能靠对着屏幕数。把它做成纯函数，就能在纯 JVM 上一条命令跑完
 * 解析/去重/夹取/排序/合并的所有边界（{@code tools/speedlistpolicy_test.py}），
 * 不必为验一条「1.234 会变成 1.23」去装一台真机（PITFALLS §41：读数口径要能被独立复算）。
 *
 * <b>存储形态</b>：conf 里是一行逗号分隔字符串（{@code playback_speed_list}），
 * 例如 {@code 0.5,0.75,1.0,1.25,1.5,1.75,2.0,2.5,3.0}。空串 = 不启用（零干预，
 * 用 B 站自带档位）。为什么用字符串而不是 int 数组：conf 解析器只认
 * boolean/int/单行字符串三类（PITFALLS §38），列表用字符串是既有词表键
 * （{@code feed_blocked_tnames} / {@code accel_custom_hosts}）已经在用的形态。
 *
 * <b>语义（写进设置页文案，不要靠猜）</b>：
 * <ul>
 *  <li>非空列表 = <b>替换</b>播放器自带档位（用户要「自定义列表」，不是「追加」；</li>
 *  <li>顺序一律<b>按速度降序</b>（与宿主原生菜单 [3.0/2.0/1.5/1.25/1.0/0.75/0.5] 一致），
 *      用户输入的顺序不保留——升序输入在面板上会变成从左到右越来越快，那不是宿主的样子；</li>
 *  <li>越界值<b>夹</b>到 {@link SpeedPolicy#MIN}..{@link SpeedPolicy#MAX}（不是丢弃：
 *      用户写 4 的意思是「尽快」，静默丢掉等于什么都没发生；夹到 3.0 后设置页回显
 *      规范化结果，用户当场能看到 4 变成了 3）；</li>
 *  <li>非数字/零/负数<b>丢弃</b>（这些没有「夹一下就对」的解释）；</li>
 *  <li>按两位小数<b>去重</b>（1.5 与 1.50 是同一档；用四舍五入后的整数键比较，
 *      避免浮点二进制表示让 1.1 和 1.1 都不相等）；</li>
 *  <li>最多 {@link #MAX_ENTRIES} 档（超出的丢掉最小的那些）：面板是横向排布，
 *      十几档会挤成一团，而且这是配置错误最常见的样子（把别的列表整段粘进来）；</li>
 *  <li>默认倍速功能开着、且当前档位表里没有那个值时，<b>补进去</b>：否则起播套用了
 *      1.75x，面板里却没有 1.75 这一档，用户想「回到默认」都没有入口，只能去设置页改。</li>
 * </ul>
 *
 * 约定：本类不持有可变静态状态；配置每次由调用方实时传入（设置页是投递热生效的）。
 */
public final class SpeedListPolicy {

    /** 一屏能舒服排下的档位数上限。超出的按降序保留前面（更大）的那些。 */
    public static final int MAX_ENTRIES = 12;

    /** 设置页输入框的占位示例（也是「宿主自带档位 + 常用扩展档」的一份现成表）。 */
    public static final String EXAMPLE = "0.5,0.75,1.0,1.25,1.5,1.75,2.0,2.5,3.0";

    /** 分隔符：中英文逗号/分号、顿号、空白都认（用户从聊天窗口粘贴过来往往是这几种混着）。 */
    private static final String SEP = "[,，;；、\\s]+";

    private SpeedListPolicy() {
    }

    /**
     * 解析用户输入的档位表。永不抛异常：任何一格坏数据都只影响它自己。
     *
     * @param raw conf/输入框原文；null 与空串都返回空数组
     * @return 按速度<b>降序</b>、已去重、已夹到 0.25..3.0、最多 {@link #MAX_ENTRIES} 档
     */
    public static float[] parse(String raw) {
        if (raw == null) {
            return new float[0];
        }
        // TreeSet<Integer> 装「两位小数后的整数键」：天然去重，再按降序取回。
        // 直接用 Float 做键会有 -0.0/NaN 的坑，而整数键的比较与排序都是确定的。
        TreeSet<Integer> keys = new TreeSet<Integer>();
        for (String part : raw.split(SEP)) {
            String t = part.trim();
            if (t.length() == 0) {
                continue;
            }
            // 容忍用户在每档后面写 x：0.75x / 2.0X（宿主菜单上就是这么显示的）
            if (t.endsWith("x") || t.endsWith("X")) {
                t = t.substring(0, t.length() - 1).trim();
            }
            float v;
            try {
                v = Float.parseFloat(t);
            } catch (Throwable ignored) {
                // 解析不了的整格丢弃，不猜（注意：欧洲小数逗号写法「1,5」会被分隔符拆成
                // 两档 1 和 5，5 再被夹成 3，得到 [3,1]——本模块不支持欧式小数逗号，
                // 设置页里用的是普通文本输入，示例固定用句点）。
                continue;
            }
            if (Float.isNaN(v) || Float.isInfinite(v) || v <= 0f) {
                continue; // 零/负数/NaN/无穷没有可解释的「夹一下就对」的目标
            }
            keys.add(Integer.valueOf(key(clamp(v))));
        }
        return toDescendingArray(keys);
    }

    /**
     * 规范化成存储/回显用的字符串（{@code 0.5,1.0,1.25}；空数组返回空串）。
     * 用「能省则省」的数字形态（3 而不是 3.00，1.25 保持 1.25）：设置页回显给人读，
     * 而 conf 只要能被 {@link #parse(String)} 原样读回来即可。
     */
    public static String format(float[] speeds) {
        if (speeds == null || speeds.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < speeds.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(formatOne(speeds[i]));
        }
        return sb.toString();
    }

    /**
     * 真正的档位表：用户表（可能为空）→ 需要时补上默认倍速 → 降序去重 → 截断。
     *
     * @param raw            用户配置原文（空 = 该功能不启用）
     * @param defaultSpeed   默认倍速（{@link SpeedPolicy#fromPct(int)} 转换后的值）
     * @param includeDefault 默认倍速功能是否开着（开着才有「把默认值补进表」的意义）
     * @return 空数组 = 不干预（调用方据此原样放行宿主生成的列表）
     */
    public static float[] compose(String raw, float defaultSpeed, boolean includeDefault) {
        TreeSet<Integer> keys = new TreeSet<Integer>();
        float[] base = parse(raw);
        for (int i = 0; i < base.length; i++) {
            keys.add(Integer.valueOf(key(base[i])));
        }
        if (keys.isEmpty()) {
            // 用户没配 = 不接管。这里**不能**把默认倍速当成唯一档位写进去：
            // 那会把整张菜单替换成一格（用户只是设了个默认倍速，没要求改菜单）。
            return new float[0];
        }
        if (includeDefault && !Float.isNaN(defaultSpeed) && !Float.isInfinite(defaultSpeed)
                && defaultSpeed > 0f) {
            keys.add(Integer.valueOf(key(clamp(defaultSpeed))));
        }
        return toDescendingArray(keys);
    }

    /** 夹到行为区间（与默认倍速共用同一对常量，避免两处各写一份 0.25/3.0）。 */
    public static float clamp(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) {
            return SpeedPolicy.NORMAL;
        }
        if (v < SpeedPolicy.MIN) {
            return SpeedPolicy.MIN;
        }
        if (v > SpeedPolicy.MAX) {
            return SpeedPolicy.MAX;
        }
        return v;
    }

    /** 两位小数的整数键（四舍五入）：1.1 与 1.1000001 在这里是同一档。 */
    public static int key(float v) {
        return Math.round(clamp(v) * 100f);
    }

    /** 单档的显示形态：最多两位小数、去掉尾随零（3.00 → "3"、1.50 → "1.5"、1.25 → "1.25"）。 */
    public static String formatOne(float v) {
        String s = String.format(Locale.US, "%.2f", Float.valueOf(clamp(v)));
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static float[] toDescendingArray(TreeSet<Integer> keys) {
        // TreeSet 升序 → 取降序；超过上限时保留**更大**的那些（用户多半是想加高速档）。
        int n = Math.min(keys.size(), MAX_ENTRIES);
        float[] out = new float[n];
        Iterator<Integer> it = keys.descendingIterator();
        for (int i = 0; i < n && it.hasNext(); i++) {
            out[i] = it.next().intValue() / 100f;
        }
        return out;
    }

    /** 仅测试/日志用：把数组打成 {@code [1.0, 1.25]} 形态。 */
    public static String debug(float[] speeds) {
        if (speeds == null) {
            return "null";
        }
        List<String> parts = new ArrayList<String>();
        for (int i = 0; i < speeds.length; i++) {
            parts.add(formatOne(speeds[i]));
        }
        return parts.toString();
    }
}
