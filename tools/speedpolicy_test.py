# -*- coding: utf-8 -*-
"""倍速决策纯逻辑单测（SpeedPolicy / SpeedConfig），红→绿可用。

被测:  app/src/main/java/com/tamer/bili/hooks/SpeedPolicy.java
       （com.tamer.bili.hooks.SpeedPolicy，纯静态方法，无 android 依赖）

跑法:  cd /root/src/BiliTamer && python3 tools/speedpolicy_test.py
       JAVA_HOME 可选，默认找 /usr/lib/jvm/java-17-openjdk-amd64，
       都没有就退回 PATH 上的 javac/java（和 accel_selftest.py 一致）。

输出纪律（PITFALLS §52）：javac 不删输出类，而本仓库 build/ 会被 d8 整个 dex 进 apk。
所以这里 **只往 /tmp 写**：源码和反射 Main 复制到临时目录，class 输出到另一个临时目录，
跑完 rmtree。仓库里除了这个 .py 本身，一个字节都不碰。

失败纪律：任何一格失败都打印「用例名 / 预期 X 实际 Y」，全部跑完再汇总，
不做「第一个断言失败就退出」——一次跑完能看到 impl 这轮还剩几处不对。
"""

import os
import shutil
import subprocess
import sys
import tempfile

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
POLICY_SRC = os.path.join(PROJ, "app", "src", "main", "java",
                          "com", "tamer", "bili", "hooks", "SpeedPolicy.java")
POLICY_CLASS = "com.tamer.bili.hooks.SpeedPolicy"

# 手动调速帧的真身。前 4 帧是 hook 自己的包装栈（biliSpeed 原写法从 i=4 起），
# 帧 index < 4 的命中不算手动，见 isManual_change_at_index_below_4。
MANUAL_CLS = "com.bilibili.player.tangram.basic.PlaySpeedManagerImpl"

# 反射 Main 里包装栈的形状：getStackTrace() 返回的数组[0] 永远是 getStackTrace 自己，
# 所以「真身落在 index 4」= 前面再垫 3 帧。
JAVA_MAIN = u"""
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 单测反射驱动：不依赖被测类在编译期存在，所以「类还没写」也能跑出干净的 RED。
 *
 * v1.10.2 起 SpeedPolicy 只剩 clamp / startupSpeed / toPct / fromPct 四个纯函数
 * （手动调速识别与会话状态机已被删除：6.7.1 dex 实证调用栈标记认不出手动调速，见 PITFALLS §63）。
 * 用例只钉「输入 → 输出」，不复制任何宿主类名——宿主锚点的真实性由 dex 实证负责，
 * 不能靠单测里的假栈自我证明（上一版就是这么漏掉 BUG 的）。
 */
public final class SpeedPolicyProbe {

    static final String P = "com.tamer.bili.hooks.SpeedPolicy";

    static Class<?> cls;
    static final List<String> lines = new ArrayList<String>();
    static int failed = 0;

    public static void main(String[] a) {
        try {
            cls = Class.forName(P);
        } catch (Throwable t) {
            System.out.println("FAIL  [" + P + "] 类未找到: "
                    + t.getClass().getName() + ": " + t.getMessage());
            System.out.println("REASON expected " + P + " (with clamp/startupSpeed/toPct/fromPct), "
                    + "actual: class not found");
            System.out.println("SUMMARY 0 passed, 1 failed (0 cases run)");
            System.exit(1);
            return;
        }

        // ---- clamp: 所有速度出口都要过这道闸 ----
        caseClamp();
        // ---- startupSpeed: 起播时该套哪个值（关闭 = 不干预）----
        caseStartupSpeed();
        // ---- toPct / fromPct: conf 只认 int，浮点必须走百分比 ----
        casePct();

        System.out.println("SUMMARY " + (lines.size() - failed) + " passed, "
                + failed + " failed (" + lines.size() + " cases)");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- clamp
    static void caseClamp() {
        // 下界 0.25 / 上界 3.0 两端各测：区间内原样，越界夹到边界。
        check("clamp_in_range_below_mid", 0.5f, invoke("clamp", fl(0.5f)));
        check("clamp_at_lower_bound_0_25", 0.25f, invoke("clamp", fl(0.25f)));
        check("clamp_just_inside_lower_bound", 0.26f, invoke("clamp", fl(0.26f)));
        check("clamp_at_upper_bound_3_0", 3.0f, invoke("clamp", fl(3.0f)));
        check("clamp_just_inside_upper_bound", 2.99f, invoke("clamp", fl(2.99f)));
        check("clamp_below_lower_bound_0_1", 0.25f, invoke("clamp", fl(0.1f)));
        check("clamp_above_upper_bound_8_0", 3.0f, invoke("clamp", fl(8.0f)));
        check("clamp_negative_minus_1", 0.25f, invoke("clamp", fl(-1.0f)));
        // NaN/inf 不是「大」也不是「小」，任何比较都假，必须显式落到 1.0f。
        check("clamp_nan_falls_back_to_1_0", 1.0f, invoke("clamp", fl(Float.NaN)));
        check("clamp_pos_inf_falls_back_to_1_0", 1.0f,
                invoke("clamp", fl(Float.POSITIVE_INFINITY)));
        check("clamp_neg_inf_falls_back_to_1_0", 1.0f,
                invoke("clamp", fl(Float.NEGATIVE_INFINITY)));
    }

    // ---------------------------------------------------------- startupSpeed
    static void caseStartupSpeed() {
        // 关闭 = 不干预：返回 1.0f 这个「不干预」参照值，调用方据此连 setPlaySpeed 都不调。
        check("startup_disabled_is_normal", 1.0f, invoke("startupSpeed", bo(false), in(250)));
        check("startup_disabled_ignores_high_pct", 1.0f, invoke("startupSpeed", bo(false), in(300)));

        // 开启：套用配置值。
        check("startup_enabled_150_is_1_5", 1.5f, invoke("startupSpeed", bo(true), in(150)));
        check("startup_enabled_200_is_2_0", 2.0f, invoke("startupSpeed", bo(true), in(200)));
        check("startup_enabled_25_lower_bound", 0.25f, invoke("startupSpeed", bo(true), in(25)));
        check("startup_enabled_300_upper_bound", 3.0f, invoke("startupSpeed", bo(true), in(300)));
        // 读取侧兜底 25..400：350 不报错，但仍被夹到行为上界 3.0f。
        check("startup_enabled_350_read_fallback_clamped_to_3_0", 3.0f,
                invoke("startupSpeed", bo(true), in(350)));
        check("startup_enabled_10_read_side_clamped_to_0_25", 0.25f,
                invoke("startupSpeed", bo(true), in(10)));
    }

    // ------------------------------------------------------------ pct 换算
    /*
     * 百分比换算的读写两条边界**不是**任务原描述里的，是 lead 裁决补的：
     *   lead 裁决：写入侧 25..300，读取侧兜底 25..400。
     * 理由：3.5x 若存成 350 再读成 3.0x，数据/界面/行为三处对不上且不报错，
     * 是最坏的不一致；"存储留余量给未来"是投机优化，代价是当下多一个静默降级洞。
     * 所以写入侧（设置页 + BiliConfig）卡 25..300；读取侧保留 25..400 夹取只作兜底
     * （旧配置/手写 conf 里的 350 不至于算出 4.0），出现时要能看出来（读取处打一条 warn）。
     * 行为边界始终是 clamp 的 0.25..3.0，没变。
     */
    static void casePct() {
        // conf 严格解析只认 int（PITFALLS #38），浮点必须落成整数百分比。
        check("toPct_1_5_is_150", Integer.valueOf(150), invoke("toPct", fl(1.5f)));
        check("toPct_1_25_rounds_to_125", Integer.valueOf(125), invoke("toPct", fl(1.25f)));
        check("toPct_2_0_is_200", Integer.valueOf(200), invoke("toPct", fl(2.0f)));
        check("toPct_1_0_is_100", Integer.valueOf(100), invoke("toPct", fl(1.0f)));
        check("toPct_0_25_lower_bound_is_25", Integer.valueOf(25), invoke("toPct", fl(0.25f)));
        check("toPct_3_0_upper_bound_is_300", Integer.valueOf(300), invoke("toPct", fl(3.0f)));
        check("toPct_0_755_rounds_half_up_to_76", Integer.valueOf(76), invoke("toPct", fl(0.755f)));
        check("toPct_0_754_truncates_to_75", Integer.valueOf(75), invoke("toPct", fl(0.754f)));
        // 写入前先夹：越界速度不能被存成一个用户没选过的百分比。
        check("toPct_3_5_write_side_clamped_to_300", Integer.valueOf(300),
                invoke("toPct", fl(3.5f)));
        check("toPct_0_1_write_side_clamped_to_25", Integer.valueOf(25),
                invoke("toPct", fl(0.1f)));
        check("toPct_nan_falls_back_to_100", Integer.valueOf(100),
                invoke("toPct", fl(Float.NaN)));

        check("fromPct_150_is_1_5", 1.5f, invoke("fromPct", in(150)));
        check("fromPct_125_is_1_25", 1.25f, invoke("fromPct", in(125)));
        check("fromPct_25_lower_bound_is_0_25", 0.25f, invoke("fromPct", in(25)));
        check("fromPct_10_below_range_clamped_to_0_25", 0.25f, invoke("fromPct", in(10)));
        check("fromPct_0_clamped_to_0_25", 0.25f, invoke("fromPct", in(0)));
        check("fromPct_negative_clamped_to_0_25", 0.25f, invoke("fromPct", in(-500)));
        check("fromPct_400_above_range_clamped_to_3_0", 3.0f, invoke("fromPct", in(400)));
        check("fromPct_300_upper_bound_is_3_0", 3.0f, invoke("fromPct", in(300)));
        // 读取侧兜底（25..400）与写入侧（25..300）的差：350 不报错、不迁移，夹到 3.0。
        check("fromPct_350_read_side_fallback_is_3_0", 3.0f, invoke("fromPct", in(350)));
    }

    // ------------------------------------------------------------ 反射工具
    static Float fl(float f) { return Float.valueOf(f); }
    static Integer in(int i) { return Integer.valueOf(i); }
    static Boolean bo(boolean b) { return Boolean.valueOf(b); }

    /** 形参类型按实参的包装类型找（本类被测方法只有 float/int/boolean 三种基本类型）。 */
    static Class<?>[] sig(Object[] args) {
        Class<?>[] t = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            t[i] = a instanceof Float ? float.class
                    : a instanceof Integer ? int.class
                    : a instanceof Boolean ? boolean.class
                    : Object.class;
        }
        return t;
    }

    static String name(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
        }
        return sb.toString();
    }

    /** 调被测静态方法；找不到/抛异常都折成一个可打印的 "ERR:..." 字符串。 */
    static Object invoke(String method, Object... args) {
        try {
            Method m = cls.getMethod(method, sig(args));
            if (!Modifier.isStatic(m.getModifiers())) {
                return "ERR:not static";
            }
            return m.invoke(null, args);
        } catch (NoSuchMethodException e) {
            return "ERR:no such method " + method + "(" + name(args) + ")";
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            return "ERR:threw " + (c == null ? "?" : c.getClass().getSimpleName()
                    + ": " + c.getMessage());
        } catch (Throwable t) {
            return "ERR:" + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    static void check(String cn, Object expect, Object actual) {
        boolean ok;
        if (expect instanceof Float && actual instanceof Number) {
            ok = Math.abs(((Float) expect).floatValue()
                    - ((Number) actual).floatValue()) < 1e-6f;
        } else {
            ok = expect.equals(actual);
        }
        if (ok) {
            lines.add("PASS  " + cn);
        } else {
            failed++;
            lines.add("FAIL  " + cn);
            lines.add("      expected: " + expect + ", actual: " + actual);
        }
    }
}
"""


def find_jdk():
    """JDK 查找顺序：BILITAMER_JDK > JAVA_HOME > 本机常用路径 > PATH。"""
    exe = ".exe" if os.name == "nt" else ""
    for env in (os.environ.get("BILITAMER_JDK"), os.environ.get("JAVA_HOME")):
        if env and os.path.exists(os.path.join(env, "bin", "javac" + exe)):
            return os.path.join(env, "bin", "javac" + exe), os.path.join(env, "bin", "java" + exe)
    for cand in ("/usr/lib/jvm/java-17-openjdk-amd64",
                 "/usr/lib/jvm/java-21-openjdk-amd64",
                 "/usr/lib/jvm/default-java"):
        if os.path.exists(os.path.join(cand, "bin", "javac")):
            return (os.path.join(cand, "bin", "javac"),
                    os.path.join(cand, "bin", "java"))
    javac = shutil.which("javac")
    java = shutil.which("java")
    if javac and java:
        return javac, java
    return None, None


def main():
    javac, java = find_jdk()
    if not javac:
        print("no JDK: set JAVA_HOME (e.g. /usr/lib/jvm/java-17-openjdk-amd64)")
        return 2

    if not os.path.exists(POLICY_SRC):
        # RED 路径：类还没被 impl-speed 写出来。给一句人能直接读的话，而不是 python 栈。
        print("RED   %s 还不存在（impl-speed 尚未落地）" % os.path.relpath(POLICY_SRC, PROJ))
        print("FAIL  [com.tamer.bili.hooks.SpeedPolicy] 类未找到: 源文件缺失 %s"
              % POLICY_SRC)
        print("REASON expected SpeedPolicy.clamp/target/isManualChange/follows/"
              "toPct/fromPct, actual: java file not found")
        print("SUMMARY 0 passed, 1 failed (0 cases run)")
        return 1

    # 一切输出都在 /tmp：绝不能让 .class 落进会被 d8 打包的位置（PITFALLS §52）。
    work = tempfile.mkdtemp(prefix="speedpolicy-test-")
    src_dir = os.path.join(work, "src")
    out_dir = os.path.join(work, "classes")
    try:
        pkg_dir = os.path.join(src_dir, "com", "tamer", "bili", "hooks")
        os.makedirs(pkg_dir)
        os.makedirs(out_dir)
        shutil.copy2(POLICY_SRC, os.path.join(pkg_dir, "SpeedPolicy.java"))
        with open(os.path.join(src_dir, "SpeedPolicyProbe.java"), "w", encoding="utf-8") as fh:
            fh.write(JAVA_MAIN)

        sources = [os.path.join(pkg_dir, "SpeedPolicy.java"),
                   os.path.join(src_dir, "SpeedPolicyProbe.java")]
        proc = subprocess.run(
            [javac, "--release", "8", "-nowarn", "-encoding", "UTF-8",
             "-d", out_dir] + sources,
            capture_output=True, text=True,
        )
        if proc.returncode != 0:
            # 语法/API 层面没编过：同样算 RED，但把 javac 的原话带上。
            text = (proc.stdout or "") + (proc.stderr or "")
            print("RED   SpeedPolicy.java 编译失败（javac --release 8）")
            for line in text.strip().splitlines()[:40]:
                print("      " + line)
            print("FAIL  [compile] 预期 javac exit 0，实际 exit %d" % proc.returncode)
            print("SUMMARY 0 passed, 1 failed (0 cases run, compile error)")
            return 1

        run = subprocess.run(
            [java, "-Dfile.encoding=UTF-8", "-ea", "-cp", out_dir, "SpeedPolicyProbe"],
            capture_output=True, text=True,
        )
        sys.stdout.write(run.stdout or "")
        if run.returncode != 0 and run.stderr:
            sys.stdout.write(run.stderr)
        return run.returncode
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
