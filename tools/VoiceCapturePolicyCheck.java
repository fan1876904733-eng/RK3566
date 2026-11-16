import com.weiguang.drcontroller.ui.VoiceCapturePolicy;
public class VoiceCapturePolicyCheck {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        check(VoiceCapturePolicy.prefixToTrim(16000, 0, 16000) == 0);
        check(VoiceCapturePolicy.prefixToTrim(16000, -1, 16000) == 0);
        check(VoiceCapturePolicy.prefixToTrim(32000, 8000, 16000) == 14400);
        check(VoiceCapturePolicy.prefixToTrim(12000, 8000, 16000) == 8000);
        check(VoiceCapturePolicy.prefixToTrim(96000, 24000, 48000) == 43200);
        check(VoiceCapturePolicy.prefixToTrim(8000, 9000, 16000) == 0);
        System.out.println("PASS follow-up prefix preservation and acknowledgement trimming checks");
    }
}
