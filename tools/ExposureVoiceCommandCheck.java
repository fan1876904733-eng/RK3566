import com.weiguang.drcontroller.ui.ExposureVoiceCommand;
public class ExposureVoiceCommandCheck {
    public static void main(String[] args) {
        String[] requests = {"把曝光时间调整为0.55秒", "曝光时间设为零点五秒", "曝光时间改成半秒"};
        long[] expected = {550, 500, 500};
        for (int i = 0; i < requests.length; i++) {
            if (!ExposureVoiceCommand.isTimeSetting(requests[i])
                    || ExposureVoiceCommand.parseMillis(requests[i]) != expected[i]) throw new AssertionError(requests[i]);
        }
        if (ExposureVoiceCommand.isValid(250) || ExposureVoiceCommand.isValid(750)
                || ExposureVoiceCommand.isValid(530) || !ExposureVoiceCommand.isValid(700)) throw new AssertionError("bounds");
        for (String s : new String[]{"使用这个模块拍摄", "用选好的模块拍照", "开始拍摄"})
            if (!ExposureVoiceCommand.isSelectedCapture(s)) throw new AssertionError(s);
        if (ExposureVoiceCommand.isSelectedCapture("帮我拍照并生成报告")) throw new AssertionError("inspection");
        System.out.println("PASS exposure duration parsing, bounds and selected capture checks");
    }
}
