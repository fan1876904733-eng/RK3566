package com.weiguang.drcontroller.ui;

/** Parsing only: never sends a hardware command. */
public final class ExposureVoiceCommand {
    private ExposureVoiceCommand() { }
    public static boolean isTimeSetting(String text) {
        String t = VoiceCommandRouter.normalize(text);
        return t.contains("曝光") && t.contains("时间")
                && t.matches(".*(调|设|改|增加|减少|加|减).*?")
                && !t.contains("怎么") && !t.contains("如何");
    }
    public static long parseMillis(String text) {
        String t = (text == null ? "" : text).replaceAll("\\s+", "").replace("半秒", "0.5秒");
        String[] digits = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        for (int i = 0; i < digits.length; i++) t = t.replace(digits[i], String.valueOf(i));
        t = t.replace("〇", "0").replace("点", ".");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)秒").matcher(t);
        if (!m.find()) return -1;
        try { return new java.math.BigDecimal(m.group(1)).multiply(new java.math.BigDecimal(1000)).longValueExact(); }
        catch (ArithmeticException ex) { return -1; }
    }
    public static boolean isValid(long millis) {
        return millis >= 300 && millis <= 700 && millis % 50 == 0;
    }
    public static boolean isSelectedCapture(String text) {
        String t = VoiceCommandRouter.normalize(text);
        if (t.contains("报告") || t.contains("分析") || t.contains("全检")
                || t.contains("不要") || t.contains("取消") || t.contains("怎么")) return false;
        return (t.contains("拍摄") || t.contains("拍照"))
                && (t.contains("模块") || t.contains("选好") || t.contains("所选")
                || t.equals("开始拍摄") || t.equals("帮我拍摄") || t.equals("帮我拍照"));
    }
}
