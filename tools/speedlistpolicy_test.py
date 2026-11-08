# -*- coding: utf-8 -*-
"""「自定义倍速档位表」纯逻辑单测（SpeedListPolicy），红→绿可用。

被测:  app/src/main/java/com/tamer/bili/hooks/SpeedListPolicy.java
       （+ 它依赖的 SpeedPolicy.java；两个类都是纯静态、无 android 依赖）

跑法:  cd <repo> && python3 tools/speedlistpolicy_test.py
       JAVA_HOME 可选，默认找 /usr/lib/jvm/java-17-openjdk-amd64，
       都没有就退回 PATH 上的 javac/java（和 speedpolicy_test.py 一致）。

输出纪律（PITFALLS §52）：javac 不删输出类，而本仓库 build/ 会被 d8 整个 dex 进 apk。
所以这里 **只往 /tmp 写**：源码复制到临时目录、class 输出到另一个临时目录，跑完 rmtree。
仓库里除了这个 .py 本身，一个字节都不碰。

失败纪律：任何一格失败都打印「用例名 / 预期 X 实际 Y」，全部跑完再汇总，
不做「第一个断言失败就退出」——一次跑完能看到 impl 这轮还剩几处不对。
"""

import os
import shutil
import subprocess
import sys
import tempfile

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HOOKS = os.path.join(PROJ, "app", "src", "main", "java", "com", "tamer", "bili", "hooks")
POLICY_SRC = os.path.join(HOOKS, "SpeedPolicy.java")
LIST_SRC = os.path.join(HOOKS, "SpeedListPolicy.java")

JAVA_MAIN = u"""
import com.tamer.bili.hooks.SpeedListPolicy;

/** 直接调被测类（不需要反射：RED 的判据是「文件不存在/编译不过」）。 */
public final class SpeedListProbe {

    static int failed = 0;
    static int passed = 0;

    static void ok(String name) {
        passed++;
        System.out.println("PASS  " + name);
    }

    static void bad(String name, Object expected, Object actual) {
        failed++;
        System.out.println("FAIL  " + name);
        System.out.println("      expected: " + expected);
        System.out.println("      actual  : " + actual);
    }

    static String arr(float[] a) {
        if (a == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(a[i]);
        }
        return sb.append(']').toString();
    }

    /** 期望值用逗号串写，省得每个用例手写 float[]。 */
    static float[] want(String s) {
        if (s.length() == 0) return new float[0];
        String[] p = s.split(",");
        float[] out = new float[p.length];
        for (int i = 0; i < p.length; i++) out[i] = Float.parseFloat(p[i].trim());
        return out;
    }

    static void check(String name, String wantStr, float[] got) {
        float[] w = want(wantStr);
        if (w.length != got.length) { bad(name, arr(w), arr(got)); return; }
        for (int i = 0; i < w.length; i++) {
            if (Math.abs(w[i] - got[i]) > 1e-6f) { bad(name, arr(w), arr(got)); return; }
        }
        ok(name);
    }

    static void checkEq(String name, Object w, Object got) {
        if (w == null ? got == null : w.equals(got)) ok(name); else bad(name, w, got);
    }

    public static void main(String[] a) {
        // ===== parse: 基本 =====
        check("parse_null_is_empty", "", SpeedListPolicy.parse(null));
        check("parse_empty_is_empty", "", SpeedListPolicy.parse(""));
        check("parse_blank_is_empty", "", SpeedListPolicy.parse("   "));
        check("parse_single", "1.5", SpeedListPolicy.parse("1.5"));
        check("parse_sorted_descending", "2.0,1.0,0.5", SpeedListPolicy.parse("0.5,1.0,2.0"));
        check("parse_dedup_two_decimals", "1.5", SpeedListPolicy.parse("1.5,1.50"));
        check("parse_accepts_x_suffix", "2.0,1.5", SpeedListPolicy.parse("1.5x,2.0X"));
        check("parse_mixed_separators", "3.0,2.0,1.5,0.5",
                SpeedListPolicy.parse("0.5，1.5；2.0、3.0"));
        check("parse_whitespace_separated", "2.0,1.0", SpeedListPolicy.parse("1.0 2.0"));
        check("parse_invalid_tokens_dropped", "1.5", SpeedListPolicy.parse("abc,,1.5,,"));
        check("parse_zero_and_negative_dropped", "1.0", SpeedListPolicy.parse("0,-1,1.0"));
        check("parse_nan_inf_dropped", "1.25", SpeedListPolicy.parse("NaN,Infinity,-Infinity,1.25"));
        check("parse_below_range_clamped", "0.25", SpeedListPolicy.parse("0.1"));
        check("parse_above_range_clamped", "3.0", SpeedListPolicy.parse("9"));
        check("parse_rounds_to_two_decimals", "1.23", SpeedListPolicy.parse("1.234"));
        check("parse_max_entries_keeps_largest",
                "3.0,2.75,2.5,2.25,2.0,1.9,1.75,1.6,1.5,1.4,1.25,1.1",
                SpeedListPolicy.parse("0.25,0.3,0.5,0.6,0.75,0.9,1.0,1.1,1.25,1.4,"
                        + "1.5,1.6,1.75,1.9,2.0,2.25,2.5,2.75,3.0"));

        // ===== format =====
        checkEq("format_empty", "", SpeedListPolicy.format(new float[0]));
        checkEq("format_null", "", SpeedListPolicy.format(null));
        checkEq("format_trims_zeros", "3,2.5,1.25,0.5",
                SpeedListPolicy.format(new float[]{3.0f, 2.5f, 1.25f, 0.5f}));
        checkEq("format_one_3", "3", SpeedListPolicy.formatOne(3.0f));
        checkEq("format_one_1_5", "1.5", SpeedListPolicy.formatOne(1.5f));
        checkEq("format_one_1_25", "1.25", SpeedListPolicy.formatOne(1.25f));
        checkEq("format_roundtrip", "3,1.75,1.25,0.75",
                SpeedListPolicy.format(SpeedListPolicy.parse("0.75, 1.25, 1.75, 3.00")));

        // ===== compose：空配置 = 不接管（即使默认倍速开着）=====
        check("compose_empty_stays_empty", "", SpeedListPolicy.compose("", 1.5f, true));
        check("compose_blank_stays_empty", "", SpeedListPolicy.compose("  ", 1.5f, true));

        // ===== compose：补默认倍速 =====
        check("compose_adds_missing_default", "1.75,1.5,1.0",
                SpeedListPolicy.compose("1.0,1.5", 1.75f, true));
        check("compose_does_not_duplicate_default", "2.0,1.5,1.0",
                SpeedListPolicy.compose("1.0,1.5,2.0", 1.5f, true));
        check("compose_ignores_default_when_switch_off", "1.5,1.0",
                SpeedListPolicy.compose("1.0,1.5", 1.75f, false));
        check("compose_clamps_default", "3.0,1.0",
                SpeedListPolicy.compose("1.0", 9.0f, true));
        check("compose_keeps_fractional_default", "1.33,1.0",
                SpeedListPolicy.compose("1.0", 1.33f, true));

        // ===== key / clamp 边界 =====
        checkEq("key_rounds", Integer.valueOf(123), Integer.valueOf(SpeedListPolicy.key(1.234f)));
        checkEq("key_clamps_high", Integer.valueOf(300), Integer.valueOf(SpeedListPolicy.key(9f)));
        checkEq("key_clamps_low", Integer.valueOf(25), Integer.valueOf(SpeedListPolicy.key(0.01f)));
        checkEq("clamp_nan_is_normal", Float.valueOf(1.0f),
                Float.valueOf(SpeedListPolicy.clamp(Float.NaN)));

        System.out.println("SUMMARY " + passed + " passed, " + failed + " failed ("
                + (passed + failed) + " cases)");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
"""


def find_tool(name):
    home = os.environ.get("JAVA_HOME", "")
    if home:
        p = os.path.join(home, "bin", name + (".exe" if os.name == "nt" else ""))
        if os.path.exists(p):
            return p
    for cand in ("/usr/lib/jvm/java-17-openjdk-amd64/bin/", "/usr/lib/jvm/java-11-openjdk-amd64/bin/"):
        p = os.path.join(cand, name)
        if os.path.exists(p):
            return p
    return shutil.which(name)


def main():
    javac = find_tool("javac")
    java = find_tool("java")
    if not javac or not java:
        print("FAIL  [env] 找不到 javac/java（设置 JAVA_HOME 或装 JDK17）")
        return 1

    missing = [p for p in (POLICY_SRC, LIST_SRC) if not os.path.exists(p)]
    if missing:
        for p in missing:
            print("RED   %s 还不存在" % os.path.relpath(p, PROJ))
        print("SUMMARY 0 passed, 1 failed (0 cases run)")
        return 1

    work = tempfile.mkdtemp(prefix="speedlist-test-")
    src_dir = os.path.join(work, "src")
    out_dir = os.path.join(work, "classes")
    try:
        pkg_dir = os.path.join(src_dir, "com", "tamer", "bili", "hooks")
        os.makedirs(pkg_dir)
        os.makedirs(out_dir)
        shutil.copy2(POLICY_SRC, os.path.join(pkg_dir, "SpeedPolicy.java"))
        shutil.copy2(LIST_SRC, os.path.join(pkg_dir, "SpeedListPolicy.java"))
        probe = os.path.join(src_dir, "SpeedListProbe.java")
        with open(probe, "w", encoding="utf-8") as fh:
            fh.write(JAVA_MAIN)

        proc = subprocess.run(
            [javac, "--release", "8", "-nowarn", "-encoding", "UTF-8", "-d", out_dir,
             os.path.join(pkg_dir, "SpeedPolicy.java"),
             os.path.join(pkg_dir, "SpeedListPolicy.java"), probe],
            capture_output=True, text=True,
        )
        if proc.returncode != 0:
            print("RED   SpeedListPolicy.java / SpeedPolicy.java 编译失败（javac --release 8）")
            text = (proc.stdout or "") + (proc.stderr or "")
            for line in text.strip().splitlines()[:40]:
                print("      " + line)
            print("SUMMARY 0 passed, 1 failed (0 cases run, compile error)")
            return 1

        run = subprocess.run(
            [java, "-Dfile.encoding=UTF-8", "-cp", out_dir, "SpeedListProbe"],
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
