package com.codex.xiaomiscale;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

public final class BmiBandView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float bmi;

    public BmiBandView(Context context) { super(context); }

    public void setBmi(float value) {
        bmi = value;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(8), right = getWidth() - dp(8), top = dp(12), height = dp(13);
        float width = right - left;
        int[] colors = {Color.rgb(68, 157, 221), Color.rgb(102, 187, 106),
                Color.rgb(250, 192, 36), Color.rgb(244, 126, 45)};
        for (int i = 0; i < 4; i++) {
            paint.setColor(colors[i]);
            RectF part = new RectF(left + width * i / 4f, top,
                    left + width * (i + 1) / 4f, top + height);
            canvas.drawRect(part, paint);
        }
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(12));
        paint.setColor(Color.argb(215, 255, 255, 255));
        String[] labels = {"偏轻", "标准", "偏重", "肥胖"};
        for (int i = 0; i < labels.length; i++)
            canvas.drawText(labels[i], left + width * (i + .5f) / 4f, top + dp(34), paint);
        if (bmi > 0) {
            float normalized = bmi <= 18.5f ? (bmi - 12f) / 6.5f * .25f
                    : bmi < 24f ? .25f + (bmi - 18.5f) / 5.5f * .25f
                    : bmi < 28f ? .5f + (bmi - 24f) / 4f * .25f
                    : .75f + Math.min(1f, (bmi - 28f) / 12f) * .25f;
            float x = left + width * Math.max(0, Math.min(1, normalized));
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, top + height / 2f, dp(8), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setColor(Color.rgb(65, 70, 75));
            canvas.drawCircle(x, top + height / 2f, dp(7), paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
