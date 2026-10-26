package com.weiguang.drcontroller.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** 最近60个采样点的轻量趋势图，不依赖第三方图表库。 */
public class RadarTrendView extends View {
    private static final int MAX_POINTS = 60;
    private final List<Float> distance = new ArrayList<>();
    private final List<Float> breath = new ArrayList<>();
    private final List<Float> heart = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RadarTrendView(Context context, AttributeSet attrs) { super(context, attrs); }

    public void addSample(float distanceCm, float breathBpm, float heartBpm) {
        append(distance, distanceCm);
        append(breath, breathBpm);
        append(heart, heartBpm);
        invalidate();
    }

    private void append(List<Float> values, float value) {
        if (values.size() >= MAX_POINTS) values.remove(0);
        values.add(value);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = 34f, top = 18f, right = getWidth() - 12f, bottom = getHeight() - 26f;
        paint.setStrokeWidth(1f);
        paint.setColor(Color.parseColor("#284B6E"));
        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            canvas.drawLine(left, y, right, y, paint);
        }
        paint.setTextSize(20f);
        paint.setColor(Color.parseColor("#7895B5"));
        canvas.drawText("60秒前", left, getHeight() - 5f, paint);
        canvas.drawText("现在", right - 38f, getHeight() - 5f, paint);
        drawSeries(canvas, distance, 0f, 160f, Color.parseColor("#00D8FF"), left, top, right, bottom);
        drawSeries(canvas, breath, 0f, 50f, Color.parseColor("#00E58B"), left, top, right, bottom);
        drawSeries(canvas, heart, 40f, 160f, Color.parseColor("#FF8A65"), left, top, right, bottom);
    }

    private void drawSeries(Canvas canvas, List<Float> values, float min, float max, int color,
                            float left, float top, float right, float bottom) {
        if (values.size() < 2) return;
        paint.setColor(color);
        paint.setStrokeWidth(3f);
        float lastX = 0f, lastY = 0f;
        boolean hasLast = false;
        for (int i = 0; i < values.size(); i++) {
            float value = values.get(i);
            if (Float.isNaN(value)) { hasLast = false; continue; }
            float x = left + (right - left) * i / Math.max(1, MAX_POINTS - 1);
            float ratio = Math.max(0f, Math.min(1f, (value - min) / (max - min)));
            float y = bottom - ratio * (bottom - top);
            if (hasLast) canvas.drawLine(lastX, lastY, x, y, paint);
            lastX = x; lastY = y; hasLast = true;
        }
    }
}
