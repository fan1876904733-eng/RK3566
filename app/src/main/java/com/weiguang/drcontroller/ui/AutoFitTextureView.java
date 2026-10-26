package com.weiguang.drcontroller.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.TextureView;

/** 保持摄像头原始宽高比，避免 TextureView 被父容器强行拉伸。 */
public class AutoFitTextureView extends TextureView {
    private int ratioWidth;
    private int ratioHeight;

    public AutoFitTextureView(Context context, AttributeSet attrs) { super(context, attrs); }

    public void setAspectRatio(int width, int height) {
        if (width <= 0 || height <= 0) return;
        ratioWidth = width;
        ratioHeight = height;
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height);
        } else if (width * ratioHeight <= height * ratioWidth) {
            setMeasuredDimension(width, width * ratioHeight / ratioWidth);
        } else {
            setMeasuredDimension(height * ratioWidth / ratioHeight, height);
        }
    }
}
