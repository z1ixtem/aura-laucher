package com.aura.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import java.io.File;

/** Фон: обои (или градиент), их размытая копия для «стекла» и акцентный цвет. */
public class Backdrop {
    public Bitmap full;
    public Bitmap blur;
    public int accent = 0xFFB9C4FF;

    public void build(Context c, int w, int h) {
        Bitmap src = null;
        try {
            File f = new File(c.getFilesDir(), "wallpaper.jpg");
            if (f.exists()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                int s = 1;
                while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = s;
                src = BitmapFactory.decodeFile(f.getPath(), o2);
            }
        } catch (Throwable t) {
            src = null;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (src != null) {
            float sc = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
            Matrix m = new Matrix();
            m.postScale(sc, sc);
            m.postTranslate((w - src.getWidth() * sc) / 2f, (h - src.getHeight() * sc) / 2f);
            cv.drawBitmap(src, m, p);
            src.recycle();
        } else {
            p.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{0xFF1B2140, 0xFF3B2A55, 0xFF10303F}, null, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, p);
        }
        cv.drawColor(0x40000000);

        int sw = Math.max(8, w / 8), sh = Math.max(8, h / 8);
        Bitmap small = Bitmap.createScaledBitmap(out, sw, sh, true);
        int[] px = new int[sw * sh];
        small.getPixels(px, 0, sw, 0, 0, sw, sh);
        long r = 0, g = 0, b = 0;
        for (int i = 0; i < px.length; i++) {
            r += (px[i] >> 16) & 255;
            g += (px[i] >> 8) & 255;
            b += px[i] & 255;
        }
        int n = px.length;
        float[] hsv = new float[3];
        Color.colorToHSV(Color.rgb((int) (r / n), (int) (g / n), (int) (b / n)), hsv);
        hsv[1] = Math.min(Math.max(hsv[1], 0.28f), 0.6f);
        hsv[2] = 0.95f;
        accent = Color.HSVToColor(hsv);

        boxBlur(px, sw, sh, 3);
        Bitmap bl = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        bl.setPixels(px, 0, sw, 0, 0, sw, sh);
        full = out;
        blur = bl;
    }

    static void boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 3; pass++) {
            line(px, tmp, w, h, r, true);
            line(tmp, px, w, h, r, false);
        }
    }

    static int ix(boolean horiz, int l, int i, int w) {
        return horiz ? l * w + i : i * w + l;
    }

    static int cl(int v, int len) {
        return v < 0 ? 0 : (v >= len ? len - 1 : v);
    }

    static void line(int[] src, int[] dst, int w, int h, int r, boolean horiz) {
        int lines = horiz ? h : w, len = horiz ? w : h, div = r * 2 + 1;
        for (int l = 0; l < lines; l++) {
            int rr = 0, g = 0, b = 0;
            for (int k = -r; k <= r; k++) {
                int c = src[ix(horiz, l, cl(k, len), w)];
                rr += (c >> 16) & 255;
                g += (c >> 8) & 255;
                b += c & 255;
            }
            for (int i = 0; i < len; i++) {
                dst[ix(horiz, l, i, w)] = 0xFF000000 | ((rr / div) << 16) | ((g / div) << 8) | (b / div);
                int ca = src[ix(horiz, l, cl(i + r + 1, len), w)];
                int cs = src[ix(horiz, l, cl(i - r, len), w)];
                rr += ((ca >> 16) & 255) - ((cs >> 16) & 255);
                g += ((ca >> 8) & 255) - ((cs >> 8) & 255);
                b += (ca & 255) - (cs & 255);
            }
        }
    }
}
