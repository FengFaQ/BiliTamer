package com.tamer.bili;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 把从宿主要回来的日志写到用户找得到的地方。
 *
 * <b>本类运行在模块自己的进程里</b>（设置页），所以：
 *  <ul>
 *   <li>写自己的外部目录 <b>不需要任何权限</b>、不需要 root、不需要 su；</li>
 *   <li>不会产生「每个进程一份文件」那种混乱；</li>
 *   <li>用户可以点设置页里的按钮直接触发，不用懂 adb。</li>
 *  </ul>
 *
 * <b>落点选择：</b>优先写公共的 {@code Download/BiliTamer/}（用户最容易找到，
 * 也能直接用数据线拷走）；做不到（Android 11+ 分区存储禁止非媒体类应用写公共目录）时
 * 退回模块自己的外部目录 {@code Android/data/com.tamer.bili/files/BiliTamer/}，
 * 并把**实际路径**回报给界面——绝不说"已保存"却不告诉用户在哪。
 */
public final class LogExport {

    public static final String DIR_NAME = "BiliTamer";

    private LogExport() {
    }

    /** 导出结果：路径与说明（给界面直接显示）。 */
    public static final class Result {
        public final boolean ok;
        public final String path;
        public final String note;

        Result(boolean ok, String path, String note) {
            this.ok = ok;
            this.path = path;
            this.note = note;
        }
    }

    /**
     * 写一份日志文件。
     *
     * @param ctx     模块 Context
     * @param content 日志全文
     * @param tag     文件名后缀（如 main / all），便于多次导出区分
     */
    public static Result write(Context ctx, String content, String tag) {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        String name = "bilitamer-" + stamp + "-" + (tag == null ? "log" : tag) + ".log";

        // ① 先试公共 Download：用户最容易找到，但 Android 11+ 会拒绝（EACCES/EROFS）。
        try {
            File dir = new File("/sdcard/Download/" + DIR_NAME);
            if (dir.isDirectory() || dir.mkdirs()) {
                File f = new File(dir, name);
                writeFile(f, content);
                return new Result(true, f.getAbsolutePath(), "已写入公共下载目录");
            }
        } catch (Throwable ignored) {
            // 分区存储拒绝是**预期**结果，不是异常情况：静默退回下一档。
        }

        // ② 退回模块自己的外部目录：任何 Android 版本都能写，且用户用文件管理器能看到。
        try {
            File base = ctx.getExternalFilesDir(null);
            if (base == null) {
                base = new File(ctx.getFilesDir(), "external");
            }
            File dir = new File(base, DIR_NAME);
            if (!dir.exists() && !dir.mkdirs()) {
                return new Result(false, dir.getAbsolutePath(), "无法创建目录");
            }
            File f = new File(dir, name);
            writeFile(f, content);
            return new Result(true, f.getAbsolutePath(), "已写入应用专属目录（公共下载被系统拒绝）");
        } catch (Throwable t) {
            return new Result(false, null,
                    "写入失败：" + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void writeFile(File f, String content) throws Exception {
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(content.getBytes("UTF-8"));
            fos.getFD().sync();
        } finally {
            fos.close();
        }
    }
}
