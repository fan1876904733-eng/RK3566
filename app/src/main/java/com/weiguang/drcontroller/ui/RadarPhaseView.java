package com.weiguang.drcontroller.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** LD6002 0x0A13真实总相位、呼吸相位和心跳相位波形。 */
public class RadarPhaseView extends View {
    private static final int MAX = 240;
    private final List<Float> total = new ArrayList<>(), breath = new ArrayList<>(), heart = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RadarPhaseView(Context context, AttributeSet attrs) { super(context, attrs); }

    public void add(float totalPhase, float breathPhase, float heartPhase) {
        append(total, totalPhase); append(breath, breathPhase); append(heart, heartPhase); invalidate();
    }

    public boolean hasData() { return !total.isEmpty(); }

    private void append(List<Float> list, float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return;
        if (list.size() >= MAX) list.remove(0);
        list.add(value);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float h = getHeight() / 3f;
        drawLane(canvas, total, 0f, h, Color.parseColor("#00D8FF"), "总相位");
        drawLane(canvas, breath, h, h * 2f, Color.parseColor("#00E58B"), "呼吸相位");
        drawLane(canvas, heart, h * 2f, h * 3f, Color.parseColor("#FF8A65"), "心跳相位");
    }

    private void drawLane(Canvas canvas, List<Float> values, float top, float bottom, int color, String label) {
        paint.setTextSize(18f); paint.setColor(Color.parseColor("#8AA4C8"));
        canvas.drawText(label, 8f, top + 20f, paint);
        float center = (top + bottom) / 2f;
        paint.setStrokeWidth(1f); paint.setColor(Color.parseColor("#284B6E"));
        canvas.drawLine(0f, center, getWidth(), center, paint);
        if (values.size() < 2) return;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : values) { min = Math.min(min, v); max = Math.max(max, v); }
        float range = Math.max(0.0001f, max - min);
        paint.setColor(color); paint.setStrokeWidth(2.5f);
        float lastX = 0f, lastY = center;
        for (int i = 0; i < values.size(); i++) {
            float x = getWidth() * i / Math.max(1f, MAX - 1f);
            float normalized = (values.get(i) - min) / range;
            float y = bottom - 6f - normalized * Math.max(1f, bottom - top - 12f);
            if (i > 0) canvas.drawLine(lastX, lastY, x, y, paint);
            lastX = x; lastY = y;
        }
    }
}
