package com.tamer.bili.hooks;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;

/**
 * 播放器倍速菜单的「自定义档位表」（在宿主构造档位列表的出口处替换整张表）。
 *
 * <b>为什么落在这里（6.7.1 / versionCode 9161100 dex 实证）：</b>
 * 播放器面板的档位不是写死的 UI，而是一个工具方法现场拼出来的：
 *
 * <pre>
 *   com.bilibili.playerbizcommonv2.utils.B#a(MediaResource, Integer, boolean, int) : ArrayList
 *     ├→ 可选 add(new t(3.0f, enabled))        // 3.0 档受实验组 + 「非离线下载」双重 gate
 *     └→ 遍历静态字段 B.a : List&lt;Float&gt;        // &lt;clinit&gt; 里 listOf(2.0f,1.5f,1.25f,1.0f,0.75f,0.5f)
 *          每档 add(new t(speed, true))
 * </pre>
 *
 * 调用点至少 5 处（旧 biliplayerv2 的 RecyclerView 面板、theseus 的 Compose 面板、一个静态中转，
 * 以及工具栏下拉菜单 / 播放设置面板等；xref 复扫结果见 docs/speed-menu-anchor-6.7.1.md §3.4）
 * 取的都是这一个方法的返回值，所以<b>只挂这一处就能覆盖全部菜单消费路径</b>，
 * 不必分别去改 RecyclerView 适配器和 Compose 状态（那两条才是每版都会被重写的部分）。
 * 方法每次调用都 `new ArrayList()`，<b>没有缓存</b>，所以「改完设置重开面板」就能看到新档位。
 *
 * 元素类 {@code com.bilibili.playerbizcommonv2.utils.t} 是 Kotlin data class
 * （字段 {@code a:F} 速度、{@code b:Z} 是否可点；构造器 {@code (FZ)V}）。
 *
 * <b>为什么不改静态字段 {@code B.a}</b>：它是 {@code static final List}，改它要碰 final 字段
 * （各版本 ART 对 final 字段写入的态度不一致），而且拿不到 {@code enabled} 语义、
 * 3.0 档的 gate 也会被绕过。改返回值是纯「后处理」，宿主内部状态一个字节都不动。
 *
 * <b>抗漂移（PITFALLS §16/§54）</b>：{@code B} 与 {@code t} 都是 R8 混淆名，每版会换。
 * 这里不靠名字下结论：
 * <ul>
 *  <li>宿主类按<b>形状</b>校验——静态方法、4 参且第 0 参是 {@code MediaResource}、
 *      第 1 参是 {@code Integer}、第 2 参 boolean、第 3 参 int、返回 {@code ArrayList}，
 *      并且同类上有一个静态 {@code List} 字段；候选名只是提示，校验不过就当没找到；</li>
 *  <li>元素类<b>从宿主真的返回的那个列表里取第一个元素的 {@code getClass()}</b>，
 *      名字一次都不用写；只有返回空列表时才退回候选名。</li>
 * </ul>
 * 找不到时行为是「零干预 + 一条 warn」，绝不猜着改别人的列表（PITFALLS §40：
 * 改写型钩子最坏是把宿主点崩，宁可不生效）。
 *
 * <b>语义</b>：配置为空 = 完全不接管（宿主原样）；非空 = 用用户表<b>替换</b>整张档位表，
 * 降序、去重、夹到 0.25..3.0、最多 {@link SpeedListPolicy#MAX_ENTRIES} 档，
 * 默认倍速开着时把默认值补进表里（否则「起播套用了 1.75x，面板里却没有 1.75」）。
 * 每条 rule 都在 {@link SpeedListPolicy} 里，本类只做「取配置 / 找到类 / 造条目」。
 *
 * 注入条目的 `enabled` 一律 {@code true}：用户把它写进配置，就是明确想点它。这会**绕过宿主
 * 对 3.0 档的实验组/下载态 gate**（宿主只在实验组且非离线下载时才给 3.0 档），是有意的——
 * 3.0 本身在 native 上限之内（PITFALLS §15），不会更高；离线下载场景的实际观感未上真机验证，
 * 见 docs/speed-menu-anchor-6.7.1.md §6/§8。
 *
 * <b>日志口径（PITFALLS §32/§41）</b>：`armed`（挂上了）与 `applied`（真的换了）分开打；
 * 每类首条必出（verbose 关也出），之后只在档位表变化时再打一条。
 */
public final class SpeedMenuHooks {

    /**
     * 宿主「档位表构造器」所在类的候选名（6.7.1 实测 {@code com.bilibili.playerbizcommonv2.utils.B}）。
     * 只作提示：真正的判定是下面的形状校验，名字命中而形状不符同样会被拒绝。
     */
    private static final String[] CANDIDATE_HOLDERS = {
            "com.bilibili.playerbizcommonv2.utils.B",
    };

    /** 档位元素类的候选名（6.7.1 实测 {@code ...utils.t}）；正常路径从返回值里反射取得。 */
    private static final String[] CANDIDATE_ITEMS = {
            "com.bilibili.playerbizcommonv2.utils.t",
    };

    /** 方法第 0 参的类型全名（宿主真实类名，R8 不混淆包名）。 */
    private static final String MEDIA_RESOURCE = "com.bilibili.lib.media.resource.MediaResource";

    private final HookApi api;
    private final ClassLoader cl;

    private final AtomicBoolean armedLogged = new AtomicBoolean(false);
    private final AtomicBoolean appliedLogged = new AtomicBoolean(false);
    private final AtomicBoolean missLogged = new AtomicBoolean(false);
    private final AtomicBoolean installed = new AtomicBoolean(false);

    /** 元素类与构造器缓存（首次成功反射后固定）；volatile：hook 可能跨线程首次进入。 */
    private volatile Constructor<?> itemCtor;
    /** 上一次真正生效的档位表（只在变化时打日志，避免每次开面板刷屏）。 */
    private volatile String lastApplied;

    public SpeedMenuHooks(HookApi api, ClassLoader cl) {
        this.api = api;
        this.cl = cl;
    }

    public void install() {
        if (!installed.compareAndSet(false, true)) {
            api.debug("speedmenu: already installed, skip");
            return;
        }
        Method builder = null;
        Class<?> holder = null;
        String hint = null;
        for (String cand : CANDIDATE_HOLDERS) {
            Class<?> c = HookUtil.cls(cl, cand);
            if (c == null) {
                continue;
            }
            Method m = findBuilder(c);
            if (m != null) {
                holder = c;
                builder = m;
                hint = cand;
                break;
            }
            api.debug("speedmenu: candidate " + cand + " present but shape mismatch, skip");
        }
        if (builder == null) {
            warnMiss("anchor miss: no class declares a static "
                    + "(MediaResource, Integer, boolean, int) -> ArrayList with a static List field. "
                    + "Tried " + java.util.Arrays.toString(CANDIDATE_HOLDERS));
            return;
        }
        api.info("speedmenu: anchor hit " + hint + "#" + builder.getName()
                + "(MediaResource,Integer,boolean,int)ArrayList");
        try {
            api.deoptimize(builder);
            api.addHook("speedmenu: " + holder.getName() + "#" + builder.getName(),
                    builder, new XposedInterface.Hooker() {
                        @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object original = chain.proceed();
                            try {
                                return rewrite(original);
                            } catch (Throwable t) {
                                // 后处理出错一律原样放行：宁可用宿主档位，也不许把播放器点崩。
                                api.debug("speedmenu: rewrite threw (pass through): " + t);
                                return original;
                            }
                        }
                    });
            if (armedLogged.compareAndSet(false, true)) {
                api.info("speedmenu: armed on " + holder.getName() + "#" + builder.getName()
                        + " (stage=M1, 档位表出口)");
            }
        } catch (Throwable t) {
            api.warn("speedmenu: hook failed: " + t);
        }
    }

    /**
     * 把宿主拼好的档位表换成用户表（未配置时原样返回）。
     *
     * @param original 宿主返回值（正常是 {@code ArrayList<元素类>}，可能为 null/空）
     * @return 替换后的列表；不需要/不能替换时返回 {@code original}
     */
    private Object rewrite(Object original) throws Throwable {
        if (!(original instanceof List)) {
            return original;
        }
        List<?> hostList = (List<?>) original;
        String raw = api.getPlaybackSpeedList();
        float[] speeds = SpeedListPolicy.compose(raw,
                SpeedPolicy.fromPct(api.getPlaybackSpeedPct()),
                api.isPlaybackSpeedEnabled());
        if (speeds.length == 0) {
            // 空配置 = 零干预。这里**不要**顺手「把列表规范化后写回」：没配置就没有可写的东西。
            return original;
        }
        Constructor<?> ctor = itemCtorOf(hostList);
        if (ctor == null) {
            warnMiss("no item constructor (FZ)V found for the host list element (element="
                    + (hostList.isEmpty() ? "empty list" : hostList.get(0).getClass().getName())
                    + "); menu list left untouched");
            return original;
        }
        ArrayList<Object> out = new ArrayList<Object>(speeds.length);
        for (int i = 0; i < speeds.length; i++) {
            out.add(ctor.newInstance(Float.valueOf(speeds[i]), Boolean.TRUE));
        }
        String applied = SpeedListPolicy.format(speeds);
        if (!applied.equals(lastApplied)) {
            lastApplied = applied;
            api.info("speedmenu: applied " + speeds.length + " entr"
                    + (speeds.length == 1 ? "y" : "ies") + " [" + applied + "]"
                    + " (host offered " + hostList.size() + ", stage=M1)");
        } else {
            api.debug("speedmenu: list rebuilt, same as last (" + applied + ")");
        }
        return out;
    }

    /** 取（并缓存）元素类的 {@code (float, boolean)} 构造器：先看宿主返回值里真是什么类。 */
    private Constructor<?> itemCtorOf(List<?> hostList) {
        Constructor<?> cached = itemCtor;
        if (cached != null) {
            return cached;
        }
        Class<?> itemCls = null;
        if (!hostList.isEmpty() && hostList.get(0) != null) {
            // 首选：宿主自己刚造出来的那个类（名字都不用知道，天然抗漂移）
            itemCls = hostList.get(0).getClass();
        } else {
            for (String cand : CANDIDATE_ITEMS) {
                Class<?> c = HookUtil.cls(cl, cand);
                if (c != null) {
                    itemCls = c;
                    break;
                }
            }
        }
        if (itemCls == null) {
            return null;
        }
        try {
            Constructor<?> c = itemCls.getDeclaredConstructor(float.class, boolean.class);
            try {
                c.setAccessible(true);
            } catch (Throwable ignored) {
                // 同进程内宿主类，setAccessible 失败也还能试；真失败会在 newInstance 抛并原样放行
            }
            itemCtor = c;
            api.info("speedmenu: item type " + itemCls.getName() + " ctor(FZ) resolved");
            return c;
        } catch (Throwable t) {
            // 形状兜底：类名对了但构造器形状换了（例如加了第三个「是否默认选中」参数）
            for (Constructor<?> c : itemCls.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 2 && p[0] == float.class && p[1] == boolean.class) {
                    c.setAccessible(true);
                    itemCtor = c;
                    return c;
                }
            }
            api.debug("speedmenu: no (FZ) ctor on " + itemCls.getName() + ": " + t);
            return null;
        }
    }

    /**
     * 在候选类上找「档位表构造器」：静态、4 参、形状对得上、返回 ArrayList，
     * 且同类上有一个静态 List 字段（<clinit> 里那份硬编码表的落点）。
     * 用形状而不是名字，是因为该方法在 6.7.1 是 {@code a}，下一版可能就叫 {@code b}。
     */
    private static Method findBuilder(Class<?> c) {
        if (!hasStaticListField(c)) {
            return null;
        }
        for (Method m : c.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 4) {
                continue;
            }
            if (!MEDIA_RESOURCE.equals(p[0].getName())) {
                continue;
            }
            if (p[1] != Integer.class || p[2] != boolean.class || p[3] != int.class) {
                continue;
            }
            if (!ArrayList.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            return m;
        }
        return null;
    }

    private static boolean hasStaticListField(Class<?> c) {
        try {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())
                        && List.class.isAssignableFrom(f.getType())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void warnMiss(String msg) {
        if (missLogged.compareAndSet(false, true)) {
            api.warn("speedmenu: " + msg);
        }
    }
}
