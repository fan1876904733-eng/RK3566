package com.weiguang.drcontroller.ui;

/** Byte counts are in the client's mono PCM16 rate, not the hardware mixer rate. */
public final class VoiceCapturePolicy {
    private VoiceCapturePolicy() { }
    public static int prefixToTrim(int length, int acknowledgementEnd, int sampleRate) {
        if (acknowledgementEnd <= 0 || acknowledgementEnd >= length) return 0;
        int tail = Math.max(0, sampleRate) * 2 / 5; // 200ms PCM16 speaker tail
        if (length - acknowledgementEnd - tail < 4000) tail = 0;
        return acknowledgementEnd + tail;
    }
}
