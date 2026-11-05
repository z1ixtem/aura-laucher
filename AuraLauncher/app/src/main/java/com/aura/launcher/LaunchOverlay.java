package com.aura.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

/**
 * Анимация запуска: стеклянное «окно» вырастает из иконки на весь экран (с лёгким 3D-наклоном),
 * затем запускается приложение. При возврате окно сжимается обратно в иконку.
 */
public class LaunchOverlay extends View {
    final float d;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    final RectF from = new RectF(), cur = new RectF(), icn = new RectF();
    final Camera cam = new Camera();
    final Matrix m = new Matrix();
    Bitmap icon;
    int accent = 0xFFB9C4FF;
    float t;
    ValueAnimator anim;
    boolean active;
    Runnable onFull;

    final Runnable timeout = new Runnable() {
        public void run() {
            close();
        }
    };

    public LaunchOverlay(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setVisibility(GONE);
    }

    public boolean isActive() {
        return active;
    }

    public void open(RectF r, Bitmap ic, int acc, Runnable full) {
        if (active) return;
        from.set(r);
        icon = ic;
        accent = acc;
        onFull = full;
        active = true;
        setVisibility(VISIBLE);
        run(0f, 1f, 380, new DecelerateInterpolator(1.6f), new Runnable() {
            public void run() {
                Runnable f = onFull;
                onFull = null;
                if (f != null) f.run();
                postDelayed(timeout, 2500);
            }
        });
    }

    public void close() {
        removeCallbacks(timeout);
        if (!active) return;
        onFull = null;
        run(t, 0f, 300, new AccelerateDecelerateInterpolator(), new Runnable() {
            public void run() {
                active = false;
                setVisibility(GONE);
            }
        });
    }

    void run(float a, float b, long dur, TimeInterpolator ti, final Runnable end) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(a, b);
        anim.setDuration(dur);
        anim.setInterpolator(ti);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator v) {
                t = (Float) v.getAnimatedValue();
                invalidate();
            }
        });
        anim.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator a) {
                if (!cancelled) end.run();
            }
        });
        anim.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return active;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!active) return;
        float W = getWidth(), H = getHeight(), e = t;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb((int) (140 * e), 0, 0, 0));
        c.drawRect(0, 0, W, H, p);

        cur.set(from.left * (1 - e), from.top * (1 - e),
                from.right + (W - from.right) * e, from.bottom + (H - from.bottom) * e);
        float rad = 22 * d * (1 - e);
        float cx = cur.centerX(), cy = cur.centerY();
        c.save();
        cam.save();
        cam.rotateX(16 * (1 - e));
        cam.getMatrix(m);
        cam.restore();
        m.preTranslate(-cx, -cy);
        m.postTranslate(cx, cy);
        c.concat(m);

        p.setColor(Color.argb((int) (150 + 105 * e), 16, 18, 22));
        c.drawRoundRect(cur, rad, rad, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * d);
        p.setColor((accent & 0x00FFFFFF) | (((int) (200 * (1 - e))) << 24));
        c.drawRoundRect(cur, rad, rad, p);

        if (icon != null) {
            float s = from.width() + (84 * d - from.width()) * e;
            float fade = Math.max(0f, 1f - Math.max(0f, (e - 0.35f) / 0.65f));
            ip.setAlpha((int) (255 * fade));
            icn.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
            c.drawBitmap(icon, null, icn, ip);
        }
        c.restore();
    }
}
