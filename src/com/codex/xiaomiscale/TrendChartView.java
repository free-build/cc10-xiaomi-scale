package com.codex.xiaomiscale;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class TrendChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<ScaleDatabase.TrendPoint> points;
    private final String unit;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd", Locale.CHINA);

    public TrendChartView(Context context, List<ScaleDatabase.TrendPoint> points, String unit) {
        super(context);
        this.points = points;
        this.unit = unit;
        setBackgroundColor(Color.TRANSPARENT);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float left = 65 * density;
        float top = 18 * density;
        float right = getWidth() - 18 * density;
        float bottom = getHeight() - 42 * density;

        if (points == null || points.isEmpty()) {
            paint.setColor(Color.rgb(102, 112, 133));
            paint.setTextSize(17 * density);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("该时间范围内没有称重记录", getWidth() / 2f,
                    getHeight() / 2f, paint);
            return;
        }

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        long minTime = points.get(0).measuredAt;
        long maxTime = points.get(points.size() - 1).measuredAt;
        for (ScaleDatabase.TrendPoint point : points) {
            float value = ScaleDatabase.convertFromKg(point.weightKg, unit);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        float padding = Math.max(0.5f, (max - min) * 0.18f);
        min -= padding;
        max += padding;

        paint.setStrokeWidth(1 * density);
        paint.setTextSize(12 * density);
        paint.setTextAlign(Paint.Align.RIGHT);
        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            float label = max - (max - min) * i / 4f;
            paint.setColor(Color.rgb(235, 238, 239));
            canvas.drawLine(left, y, right, y, paint);
            paint.setColor(Color.rgb(132, 141, 148));
            canvas.drawText(String.format(Locale.CHINA, "%.1f", label),
                    left - 10 * density, y + 5 * density, paint);
        }

        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(dateFormat.format(new Date(minTime)), left, bottom + 28 * density, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(dateFormat.format(new Date(maxTime)), right, bottom + 28 * density, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(unit, 12 * density, top, paint);

        Path path = new Path();
        float[] xs = new float[points.size()];
        float[] ys = new float[points.size()];
        for (int i = 0; i < points.size(); i++) {
            ScaleDatabase.TrendPoint point = points.get(i);
            // The trend is a sequence of actual weigh-ins, not a time-scale chart.
            // Every record gets the same horizontal spacing regardless of the
            // number of minutes or days between adjacent measurements.
            float x = points.size() == 1
                    ? (left + right) / 2f
                    : left + (right - left) * i / (float) (points.size() - 1);
            float value = ScaleDatabase.convertFromKg(point.weightKg, unit);
            float y = bottom - (bottom - top) * (value - min) / (max - min);
            xs[i] = x;
            ys[i] = y;
            if (i == 0) path.moveTo(x, y);
            else {
                float midX = (xs[i - 1] + x) / 2f;
                path.cubicTo(midX, ys[i - 1], midX, y, x, y);
            }
        }

        if (points.size() > 1) {
            Path fill = new Path(path);
            fill.lineTo(xs[points.size() - 1], bottom);
            fill.lineTo(xs[0], bottom);
            fill.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, top, 0, bottom,
                    Color.argb(70, 0, 190, 131), Color.argb(4, 0, 190, 131),
                    Shader.TileMode.CLAMP));
            canvas.drawPath(fill, paint);
            paint.setShader(null);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.8f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(Color.rgb(0, 190, 131));
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < points.size(); i++) {
            paint.setColor(Color.WHITE);
            canvas.drawCircle(xs[i], ys[i], 5.3f * density, paint);
            paint.setColor(Color.rgb(0, 190, 131));
            canvas.drawCircle(xs[i], ys[i], 3.4f * density, paint);
        }
    }
}
