package com.weiguang.drcontroller.ui;

import java.util.Locale;

/** Pure command matching shared by local speech/text handling and regression checks. */
public final class VoiceCommandRouter {
    public enum Action {
        NONE, HOME, CAMERA, THERMAL, RADAR, EXPOSURE, XRAY, AI, HISTORY, ASSISTANT,
        CASE_DETAIL, REPORT, ANALYZE, BACK
    }

    private VoiceCommandRouter() { }

    public static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。！？、,.!?：:]", "")
                .replace("暴光", "曝光").replace("爆光", "曝光");
    }

    public static Action match(String text) {
        String t = normalize(text);
        if (has(t, "怎么", "如何", "为什么", "有没有", "多少", "几条")) {
            return Action.NONE;
        }
        if (has(t, "取消", "停止", "关闭", "断开", "不要", "别")) return Action.NONE;
        if (has(t, "返回上一级", "回到上一级", "返回上一页", "回到上一页", "返回上页", "返回", "退回")
                && !has(t, "首页", "主页", "历史", "摄像头", "热成像", "雷达", "分析")) {
            return Action.BACK;
        }
        boolean navigation = has(t, "打开", "进入", "切到", "切换", "跳到", "跳转", "转到",
                "转去", "显示", "查看", "查询", "看看", "看下", "回到", "返回", "调出", "调取", "前往");
        boolean capture = has(t, "拍照", "拍摄", "拍一", "帮我拍", "全检")
                && !(navigation && has(t, "页面", "界面", "拍照页"));
        boolean analysis = has(t, "分析", "生成报告", "出报告", "识别一下", "识别这张");
        boolean analyzeAction = has(t, "帮我分析", "进行", "开始", "重新", "分析一下", "分析这",
                "分析当前", "分析刚", "分析最新", "生成报告", "出报告", "识别一下", "识别这张");
        if (analysis && analyzeAction && !capture && !has(t, "页面", "界面", "功能在哪")) {
            return Action.ANALYZE;
        }
        // Capture and read-aloud commands keep their dedicated handlers.
        if (capture || has(t, "读取", "朗读", "读一下", "念一下", "选择", "只拍", "开始曝光")) {
            return Action.NONE;
        }
        boolean caseReference = hasSpecificCaseReference(t);
        if ((navigation || t.equals("查询")) && has(t, "报告", "分析结果")) return Action.REPORT;
        // Viewing a case's "AI analysis" means its saved report. Only an explicit
        // request for the analysis page opens the case-selection/analysis form.
        if (navigation && analysis && referencesCase(t)) {
            return has(t, "分析页面", "分析界面", "分析页") ? Action.AI : Action.REPORT;
        }
        if (navigation && caseReference && has(t, "案例", "记录", "影像", "分析")) {
            return Action.CASE_DETAIL;
        }
        if (!navigation && !t.endsWith("页面") && !t.endsWith("界面")) return Action.NONE;
        if (has(t, "热成像", "热传感", "热感", "热像", "红外")) return Action.THERMAL;
        if (has(t, "毫米波", "毫米玻", "豪米波", "毫米博", "雷达", "雷打")) return Action.RADAR;
        if (has(t, "准备曝光", "曝光准备", "曝光页面", "曝光界面")) return Action.EXPOSURE;
        if (has(t, "xray", "x-ray", "x光", "x射线", "射线")) return Action.XRAY;
        if (has(t, "摄像头", "可见光", "实时图", "拍照页", "预览")) return Action.CAMERA;
        if (has(t, "ai分析", "智能分析", "分析页面", "分析界面", "打开分析")) return Action.AI;
        if (has(t, "历史", "记录列表")) return Action.HISTORY;
        if (has(t, "助手", "小农", "对话", "聊天")) return Action.ASSISTANT;
        if (has(t, "首页", "主页", "主界面")) return Action.HOME;
        return Action.NONE;
    }

    /** Shared with the dispatcher so case selection is not lost for time/latest references. */
    public static boolean referencesCase(String text) {
        String t = normalize(text);
        return hasSpecificCaseReference(t) || has(t, "历史记录", "历史案例");
    }

    private static boolean hasSpecificCaseReference(String t) {
        return has(t, "第", "最新", "最近", "最后", "刚才", "刚刚", "刚拍", "这条", "那条",
                "某条", "上一条", "上次", "当前", "这个", "那个", "已选", "选中",
                "案例详情", "案例影像", "今天", "昨天", "前天", "上周", "本周", "这周")
                || t.matches(".*\\d{1,2}月\\d{1,2}[日号].*");
    }

    private static boolean has(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) return true;
        }
        return false;
    }
}
