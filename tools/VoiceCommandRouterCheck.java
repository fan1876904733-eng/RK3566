import com.weiguang.drcontroller.ui.VoiceCommandRouter;
import static com.weiguang.drcontroller.ui.VoiceCommandRouter.Action.*;

/** Run with javac/java without an emulator or paid API requests. */
public final class VoiceCommandRouterCheck {
    private static int checked;

    private static void check(String input, VoiceCommandRouter.Action expected) {
        VoiceCommandRouter.Action actual = VoiceCommandRouter.match(input);
        if (actual != expected) {
            throw new AssertionError(input + " expected " + expected + " but got " + actual);
        }
        checked++;
    }

    public static void main(String[] args) {
        check("打开摄像头", CAMERA);
        check("打开拍照页面", CAMERA);
        check("进入拍照页", CAMERA);
        check("帮我打开热传感摄像头页面", THERMAL);
        check("帮我切换到可见光页面", CAMERA);
        check("打开热传感摄像头", THERMAL);
        check("进入红外热像仪", THERMAL);
        check("显示热成像页面", THERMAL);
        check("打开毫米波雷达", RADAR);
        check("看下毫米玻璃的页面", RADAR);
        check("进入准备暴光页面", EXPOSURE);
        check("打开 X-Ray 页面", XRAY);
        check("切到X光界面", XRAY);
        check("打开 AI 分析", AI);
        check("前往分析页面", AI);
        check("打开历史记录", HISTORY);
        check("回到首页", HOME);
        check("打开小农助手", ASSISTANT);
        check("返回上一级", BACK);
        check("从报告页返回上一页", BACK);
        check("返回历史记录", HISTORY);
        check("查看第一条历史记录", CASE_DETAIL);
        check("打开第三条的分析报告", REPORT);
        check("打开第一条历史记录的AI分析", REPORT);
        check("我想打开某条历史记录的ai分析", REPORT);
        check("帮我查看第二条记录的智能分析", REPORT);
        check("打开最新一条的AI分析", REPORT);
        check("查看最近的历史记录的AI分析", REPORT);
        check("打开最后一条历史记录的AI分析", REPORT);
        check("打开那条历史记录的AI分析", REPORT);
        check("打开这条历史记录的AI分析", REPORT);
        check("打开当前案例的AI分析", REPORT);
        check("打开选中案例的AI分析", REPORT);
        check("打开历史记录的AI分析", REPORT);
        check("打开上次历史记录的AI分析", REPORT);
        check("打开昨天历史记录的AI分析", REPORT);
        check("打开11月14号历史记录的AI分析", REPORT);
        check("打开刚才拍的AI分析", REPORT);
        check("打开当前案例的分析页面", AI);
        check("打开第二条历史记录的AI分析页面", AI);
        check("打开最新一条的分析页面", AI);
        check("打开昨天历史记录的分析页面", AI);
        check("打开11月14号历史记录的分析页面", AI);
        check("打开历史记录页面", HISTORY);
        check("查询报告", REPORT);
        check("帮我把当前报告调出来", REPORT);
        check("开始AI分析", ANALYZE);
        check("AI分析一下", ANALYZE);
        check("帮我分析这张", ANALYZE);
        check("对第二条历史记录进行AI分析", ANALYZE);
        check("帮我生成报告", ANALYZE);
        check("重新分析当前案例", ANALYZE);
        check("能不能帮我打开热成像", THERMAL);
        check("热成像有什么作用", NONE);
        check("如何进行AI分析", NONE);
        check("历史记录有几条", NONE);
        check("停止分析任务", NONE);
        check("删除第一条历史记录", NONE);
        check("拍照并进行AI分析", NONE);
        check("帮我拍照并生成报告", NONE);
        check("选择摄像头和热成像", NONE);
        check("开始曝光", NONE);
        check("关闭射线源", NONE);
        check("读取当前分析报告", NONE);
        check("读取第一条历史记录的AI分析", NONE);
        check("帮我打开第一条历史记录的AI分析报告并帮我读出来", NONE);
        check("帮我念第一条历史记录的报告", NONE);
        check("播报第二条的AI分析报告", NONE);
        check("开始读取第一条的分析报告", NONE);
        check("打开第二条历史记录并进行AI分析", ANALYZE);
        check("你好", NONE);
        check(null, NONE);
        System.out.println("PASS " + checked + " voice navigation/analysis regression checks");
    }
}
