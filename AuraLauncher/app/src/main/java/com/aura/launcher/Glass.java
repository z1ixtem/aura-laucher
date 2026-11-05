package com.aura.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Стеклянные диалоги в стиле лаунчера: размытые обои, тонкая рамка, акцентная подсветка нажатия. */
public class Glass {
    public interface Pick {
        void on(int which);
    }

    static class Panel extends FrameLayout {
        final Backdrop bd;
        final float d;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        final Path clip = new Path();
        final RectF rf = new RectF(), dst = new RectF();
        final int[] loc = new int[2];
        Shader sheen;

        Panel(Context c, Backdrop bd) {
            super(c);
            this.bd = bd;
            d = c.getResources().getDisplayMetrics().density;
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = 24 * d;
            rf.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(rf, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            if (bd.blur != null && bd.full != null) {
                getLocationOnScreen(loc);
                dst.set(-loc[0], -loc[1], -loc[0] + bd.full.getWidth(), -loc[1] + bd.full.getHeight());
                c.drawBitmap(bd.blur, null, dst, bmp);
            }
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            p.setColor(0xCC101216);
            c.drawRect(rf, p);
            if (sheen == null) sheen = new LinearGradient(0, 0, 0, 90 * d, 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            p.setShader(sheen);
            c.drawRect(0, 0, w, 90 * d, p);
            p.setShader(null);
            c.restore();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d);
            p.setColor(0x55FFFFFF);
            c.drawRoundRect(rf, r, r, p);
        }
    }

    static int dp(float d, float v) {
        return (int) (v * d + 0.5f);
    }

    public static void menu(final Activity a, Backdrop bd, String title, String[] items, final Pick cb) {
        final float d = a.getResources().getDisplayMetrics().density;
        final Dialog dlg = new Dialog(a);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final Panel panel = new Panel(a, bd);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(d, 10), 0, dp(d, 10));
        int accent = bd.accent;

        if (title != null) {
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextColor(accent);
            t.setTextSize(13);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(dp(d, 22), dp(d, 10), dp(d, 22), dp(d, 8));
            col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            if (i > 0 || title != null) {
                View line = new View(a);
                line.setBackgroundColor(0x1AFFFFFF);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
                lp.leftMargin = dp(d, 22);
                lp.rightMargin = dp(d, 22);
                col.addView(line, lp);
            }
            TextView tv = new TextView(a);
            tv.setText(items[i]);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(16);
            tv.setPadding(dp(d, 22), dp(d, 15), dp(d, 22), dp(d, 15));
            StateListDrawable sl = new StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable((accent & 0x00FFFFFF) | 0x38000000));
            sl.addState(new int[]{}, new ColorDrawable(0));
            tv.setBackground(sl);
            tv.setClickable(true);
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    dlg.dismiss();
                    cb.on(idx);
                }
            });
            col.addView(tv, new LinearLayout.LayoutParams(-1, -2));
        }
        panel.addView(col, new FrameLayout.LayoutParams(-1, -2));
        dlg.setContentView(panel);

        Window w = dlg.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0));
        w.setWindowAnimations(0);
        w.setDimAmount(0.5f);
        int sw = a.getResources().getDisplayMetrics().widthPixels;
        w.setLayout(Math.min((int) (sw * 0.84f), dp(d, 340)), WindowManager.LayoutParams.WRAP_CONTENT);
        w.setGravity(Gravity.CENTER);
        panel.setAlpha(0f);
        panel.setScaleX(0.9f);
        panel.setScaleY(0.9f);
        dlg.show();
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start();
    }
}
