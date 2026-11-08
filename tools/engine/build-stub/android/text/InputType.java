package android.text;

/**
 * 编译期桩：只提供功能设置页用到的几个常量。
 *
 * 为什么需要这个桩：设置页的倍速输入框要 `setInputType(TYPE_CLASS_NUMBER |
 * TYPE_NUMBER_FLAG_DECIMAL)` 才会弹出数字键盘。没有这个桩，`setInputType` 连编译都过不去，
 * 只剩「纯文本框 + parseFloat 校验」这一条路——那对用户是可见的退化（要为 1.5 弹出全键盘）。
 *
 * 数值与 AOSP `android.text.InputType` 常量逐字一致；桩只在编译期参与，
 * 不进 APK（见 tools/engine/NOTICE.md），运行时用的永远是 ROM 里的真实类。
 */
public class InputType {
    public static final int TYPE_CLASS_TEXT = 0x00000001;
    public static final int TYPE_CLASS_NUMBER = 0x00000002;
    public static final int TYPE_NUMBER_FLAG_DECIMAL = 0x00002000;

    private InputType() {
    }
}
