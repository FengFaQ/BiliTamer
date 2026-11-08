package com.tamer.bili;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 内存日志环（宿主进程里的一份滚动缓冲）。
 *
 * <b>它解决什么问题：</b>用户要的是「点一下就有日志」，而不是去学 adb、也不是让 B 站进程
 * 反复起 {@code su} 往 /sdcard 写（那是上一版的做法：每个进程一个 su、多份文件、
 * 还得给 B 站本身授权 root —— 把简单的事情做出了四个坑）。
 *
 * <b>现在的做法：</b>宿主进程的日志照常进 logcat，同时**留一份在内存**（这个环）。
 * 用户在模块设置页按「导出日志」时，模块通过既有广播通道向宿主要这份缓冲，
 * 拿回来之后由**模块自己的进程**写成文件（写自己的目录，零权限）。
 * 好处：不需要 root、不产生多份文件、不需要用户去找路径。
 *
 * <b>为什么是环而不是无界列表：</b>宿主是长驻进程，播一晚上视频会产生大量日志；
 * 无界增长就是把用户的手机内存吃光。留最近 {@link #CAP} 行，足够覆盖「刚才那次起播」。
 *
 * <b>线程安全：</b>日志来自播放线程、主线程等多个线程，所有访问都持锁。
 * 加锁的临界区只有入队/取快照，不涉及 IO，不会拖慢宿主。
 */
public final class LogRing {

    /**
     * 保留行数。1000 行大约覆盖几百次起播的 `speed:` 行，
     * 而体积只有 ~150 KB，广播传输（Intent extra）也扛得住——
     * 超过 Binder 事务上限（1 MB）会直接抛 TransactionTooLarge。
     */
    public static final int CAP = 1000;

    private static final ArrayDeque<String> LINES = new ArrayDeque<String>(CAP);

    private LogRing() {
    }

    /** 追加一行（附时间戳）。永不抛异常。 */
    public static void add(String line) {
        if (line == null) {
            return;
        }
        String e = now() + " " + line;
        synchronized (LINES) {
            if (LINES.size() >= CAP) {
                LINES.pollFirst();
            }
            LINES.addLast(e);
        }
    }

    /**
     * 取最近 {@code maxLines} 行的快照（新的在后）。
     *
     * 导出时**截断**：宁可少给一点，也不能让 Intent extra 撑破 Binder 事务上限
     * （那会抛 TransactionTooLargeException 并且整条广播丢失，用户看到的又是一句"没反应"）。
     */
    public static String snapshot(int maxLines) {
        List<String> out = new ArrayList<String>();
        synchronized (LINES) {
            int skip = Math.max(0, LINES.size() - maxLines);
            int i = 0;
            for (String s : LINES) {
                if (i++ < skip) {
                    continue;
                }
                out.add(s);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String s : out) {
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    public static int size() {
        synchronized (LINES) {
            return LINES.size();
        }
    }

    private static String now() {
        return new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(new java.util.Date());
    }
}
