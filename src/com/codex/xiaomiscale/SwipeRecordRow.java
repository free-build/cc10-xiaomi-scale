package com.codex.xiaomiscale;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

public final class SwipeRecordRow extends FrameLayout {
    public interface Listener {
        void onEdit(SwipeRecordRow row);
        void onDelete(SwipeRecordRow row);
    }

    private final View foreground;
    private final float actionWidth;
    private float downX;
    private float downY;
    private float startTranslation;
    private boolean dragging;

    public SwipeRecordRow(Context context, View content, final Listener listener) {
        super(context);
        actionWidth = dp(184);
        setBackground(rounded(Color.TRANSPARENT, 10));
        setClipToOutline(true);
        setClipChildren(true);

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        Button edit = actionButton("修改", Color.rgb(46, 132, 230));
        Button delete = actionButton("删除", Color.rgb(238, 75, 75), true);
        actions.addView(edit, new LinearLayout.LayoutParams(dp(92),
                ViewGroup.LayoutParams.MATCH_PARENT));
        actions.addView(delete, new LinearLayout.LayoutParams(dp(92),
                ViewGroup.LayoutParams.MATCH_PARENT));
        LayoutParams actionParams = new LayoutParams((int) actionWidth,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT);
        addView(actions, actionParams);

        foreground = content;
        addView(foreground, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        edit.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                close();
                listener.onEdit(SwipeRecordRow.this);
            }
        });
        delete.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { listener.onDelete(SwipeRecordRow.this); }
        });
        setClickable(true);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                startTranslation = foreground.getTranslationX();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.abs(dx) > dp(8) && Math.abs(dx) > Math.abs(dy)) {
                    dragging = true;
                    return true;
                }
                break;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                startTranslation = foreground.getTranslationX();
                return true;
            case MotionEvent.ACTION_MOVE:
                float target = startTranslation + event.getX() - downX;
                foreground.setTranslationX(Math.max(-actionWidth, Math.min(0, target)));
                dragging = true;
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                if (foreground.getTranslationX() < -actionWidth * .35f) open();
                else close();
                dragging = false;
                return true;
        }
        return super.onTouchEvent(event);
    }

    public void open() { foreground.animate().translationX(-actionWidth).setDuration(160).start(); }
    public void close() { foreground.animate().translationX(0).setDuration(160).start(); }
    private Button actionButton(String label, int color) {
        return actionButton(label, color, false);
    }

    private Button actionButton(String label, int color, boolean roundRightCorners) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(17);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        if (roundRightCorners) {
            float radius = dp(10);
            background.setCornerRadii(new float[]{
                    0, 0, radius, radius, radius, radius, 0, 0
            });
        }
        button.setBackground(background);
        return button;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }
}
