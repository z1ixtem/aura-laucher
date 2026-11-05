package com.aura.launcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Рабочий стол. Вид 1 — бесконечный холст (пан, зум щипком, стеклянные карточки).
 * Вид 2 — «десктоп»: верхняя панель, окна с заголовками, док снизу.
 */
public class CanvasView extends View {
    public interface Host {
        void openDrawer();
        void showMenu();
        void askAccess();
        List<AppRepo.App> pinned();
        AppRepo.App appOf(String pkg);
        void launch(AppRepo.App a, float x, float y, float w, float h);
    }

    static final int CLOCK = 0, INFO = 1, MUSIC = 2, DOCK = 3, STATS = 4, HELP = 5, APPT = 6, TERM = 7;
    static final int BIG = 1000000000;
    static final float ZMIN = 0.35f, ZMAX = 2.5f, SMIN = 0.5f, SMAX = 2.5f;

    static class Card {
        final int type;
        float x, y, w, h, s = 1f;
        String pkg;
        boolean hidden;
        Shader sheen;

        Card(int t, float x, float y, float w, float h) {
            type = t;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    public final Backdrop bd = new Backdrop();
    public boolean desk2;
    final Host host;
    final NowPlaying music;
    final float d;
    final ArrayList<Card> cards = new ArrayList<Card>();
    final OverScroller scroller;
    final ScaleGestureDetector sgd;
    final int slop, minFling, maxFling;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    final Path clipP = new Path(), tmpP = new Path(), sealP = new Path();
    final RectF rf = new RectF(), dst = new RectF();
    final Rect srcR = new Rect();
    final Typeface tfBold = Typeface.create("sans-serif-medium", Typeface.BOLD);
    final Typeface tfReg = Typeface.create("sans-serif", Typeface.NORMAL);

    float panX, panY, zoom = 1f, phase, ramFrac, storFrac, waveAmp, lastFx, lastFy;
    int batLvl = 100;
    boolean batChg, is24 = true;
    String tag = "CP";
    String[] termL = new String[0];

    // жесты
    VelocityTracker vt;
    ValueAnimator panAnim;
    float downX, downY, lastX, lastY, startS, startDist;
    int mode; // 0 — ждём, 1 — холст, 2 — тащим карточку, 3 — игнор, 4 — щипок, 5 — меняем размер
    boolean longFired;
    Card hit, sel;
    long lastTap;

    final Runnable longPress = new Runnable() {
        public void run() {
            if (mode != 0) return;
            longFired = true;
            if (hit != null) {
                mode = 2;
                cards.remove(hit);
                cards.add(hit);
                sel = hit;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                invalidate();
            } else {
                mode = 3;
                host.showMenu();
            }
        }
    };

    final Runnable tick = new Runnable() {
        public void run() {
            readStats();
            invalidate();
            postDelayed(this, 1000);
        }
    };

    public CanvasView(Context c, Host h, NowPlaying m) {
        super(c);
        host = h;
        music = m;
        d = c.getResources().getDisplayMetrics().density;
        scroller = new OverScroller(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        slop = vc.getScaledTouchSlop();
        minFling = vc.getScaledMinimumFlingVelocity();
        maxFling = vc.getScaledMaximumFlingVelocity();
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector g) {
                lastFx = g.getFocusX();
                lastFy = g.getFocusY();
                removeCallbacks(longPress);
                if (mode == 2) saveCards();
                mode = 4;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector g) {
                float nz = Math.max(ZMIN, Math.min(ZMAX, zoom * g.getScaleFactor()));
                float fx = g.getFocusX(), fy = g.getFocusY();
                panX = fx - ((lastFx - panX) / zoom) * nz;
                panY = fy - ((lastFy - panY) / zoom) * nz;
                zoom = nz;
                lastFx = fx;
                lastFy = fy;
                invalidate();
                return true;
            }
        });
        sgd.setQuickScaleEnabled(false);
        desk2 = vp().getBoolean("desk2", false);
        loadCards();
        loadView();
        readStats();
    }

    // ---------- хранилище ----------

    SharedPreferences cp() {
        return getContext().getSharedPreferences("cards", 0);
    }

    SharedPreferences vp() {
        return getContext().getSharedPreferences("view", 0);
    }

    void saveView() {
        if (desk2) return;
        vp().edit().putFloat("zoomC", zoom).putFloat("px" + tag, panX).putFloat("py" + tag, panY).apply();
    }

    void loadView() {
        if (desk2) {
            zoom = 1f;
            panX = 0;
            panY = 0;
            return;
        }
        SharedPreferences v = vp();
        zoom = Math.max(ZMIN, Math.min(ZMAX, v.getFloat("zoomC", 1f)));
        panX = v.getFloat("px" + tag, 0f);
        panY = v.getFloat("py" + tag, 0f);
    }

    // ---------- карточки ----------

    float tb() {
        return 24 * d;
    }

    void loadCards() {
        cards.clear();
        sel = null;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean land = dm.widthPixels > dm.heightPixels;
        tag = (desk2 ? "D" : "C") + (land ? "L" : "P");
        float sw = dm.widthPixels / d;
        float T = 24;
        if (!desk2) {
            if (land) {
                add(CLOCK, 24, 56, 170, 170, 1f, false);
                add(INFO, 206, 56, 200, 170, 1f, false);
                add(MUSIC, 422, 56, Math.min(sw - 446, 420), 160, 1f, false);
                add(DOCK, 24, 242, 382, 92, 1f, false);
            } else {
                add(CLOCK, 16, 72, 170, 170, 1f, false);
                add(INFO, 200, 72, sw - 216, 170, 1f, false);
                add(MUSIC, 16, 258, sw - 32, 160, 1f, false);
                add(DOCK, 16, 434, sw - 32, 92, 1f, false);
            }
            add(STATS, sw + 16, land ? 56 : 72, 300, 170, 1f, false);
            add(HELP, sw + 16, land ? 242 : 258, 330, 190, 1f, false);
        } else if (land) {
            add(TERM, 16, 44, 380, 234, 1f, false);
            add(MUSIC, 412, 44, 420, 160 + T, 1f, false);
            add(CLOCK, sw - 150, 246, 170, 170 + T, 0.7f, false);
            add(INFO, 412, 250, 200, 170 + T, 0.7f, true);
            add(STATS, 60, 60, 300, 170 + T, 1f, true);
            add(HELP, 80, 40, 330, 190 + T, 1f, true);
        } else {
            add(TERM, 14, 84, sw - 28, 236, 1f, false);
            add(CLOCK, 14, 336, 170, 170 + T, 1f, false);
            add(INFO, 196, 336, sw - 210, 170 + T, 1f, false);
            add(MUSIC, 14, 552, sw - 28, 160 + T, 1f, false);
            add(STATS, 30, 300, 300, 170 + T, 1f, true);
            add(HELP, 20, 200, 330, 190 + T, 1f, true);
        }

        String s = cp().getString("apps" + tag, "");
        for (String it : s.split(";")) {
            String[] f = it.split(",");
            if (f.length < 3) continue;
            try {
                Card k = new Card(APPT, Float.parseFloat(f[1]), Float.parseFloat(f[2]), 68 * d, 68 * d);
                if (f.length > 3) k.s = Float.parseFloat(f[3]);
                k.pkg = f[0];
                cards.add(k);
            } catch (NumberFormatException e) {
                // пропускаем битую запись
            }
        }
    }

    void add(int t, float x, float y, float w, float h, float s, boolean hid) {
        SharedPreferences pr = cp();
        Card k = new Card(t, x * d, y * d, w * d, h * d);
        k.s = s;
        k.hidden = hid;
        String key = t + tag;
        if (pr.contains("x" + key)) {
            k.x = pr.getFloat("x" + key, k.x);
            k.y = pr.getFloat("y" + key, k.y);
            k.s = pr.getFloat("s" + key, s);
            k.hidden = pr.getBoolean("h" + key, hid);
        }
        cards.add(k);
    }

    void saveCards() {
        SharedPreferences.Editor e = cp().edit();
        StringBuilder sb = new StringBuilder();
        for (Card k : cards) {
            if (k.type == APPT) {
                if (sb.length() > 0) sb.append(';');
                sb.append(k.pkg).append(',').append(k.x).append(',').append(k.y).append(',').append(k.s);
            } else {
                String key = k.type + tag;
                e.putFloat("x" + key, k.x);
                e.putFloat("y" + key, k.y);
                e.putFloat("s" + key, k.s);
                e.putBoolean("h" + key, k.hidden);
            }
        }
        e.putString("apps" + tag, sb.toString());
        e.apply();
    }

    public void resetLayout() {
        boolean m = desk2;
        cp().edit().clear().apply();
        vp().edit().clear().putBoolean("desk2", m).apply();
        loadCards();
        recenter();
        invalidate();
    }

    public void setDesk2(boolean on) {
        if (on == desk2) return;
        saveCards();
        saveView();
        desk2 = on;
        vp().edit().putBoolean("desk2", on).apply();
        if (panAnim != null) panAnim.cancel();
        scroller.forceFinished(true);
        loadCards();
        loadView();
        invalidate();
    }

    public void showAllWindows() {
        for (Card k : cards) k.hidden = false;
        saveCards();
        invalidate();
    }

    /** Добавляет иконку приложения в центр экрана. false — если она уже на холсте. */
    public boolean addAppTile(String pkg) {
        for (Card k : cards) if (k.type == APPT && pkg.equals(k.pkg)) return false;
        float w = 68 * d;
        Card k = new Card(APPT, (getWidth() / 2f - panX) / zoom - w / 2, (getHeight() / 2f - panY) / zoom - w / 2, w, w);
        k.pkg = pkg;
        cards.add(k);
        saveCards();
        invalidate();
        return true;
    }

    public void reloadBackdrop() {
        if (getWidth() > 0 && getHeight() > 0) {
            bd.build(getContext(), getWidth(), getHeight());
            invalidate();
        }
    }

    public void recenter() {
        if (desk2) {
            sel = null;
            invalidate();
            return;
        }
        if (panAnim != null) panAnim.cancel();
        final float sx = panX, sy = panY, sz = zoom;
        panAnim = ValueAnimator.ofFloat(0f, 1f);
        panAnim.setDuration(350);
        panAnim.setInterpolator(new DecelerateInterpolator());
        panAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                panX = sx * (1 - f);
                panY = sy * (1 - f);
                zoom = sz + (1f - sz) * f;
                invalidate();
            }
        });
        panAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                saveView();
            }
        });
        panAnim.start();
    }

    Card hitCard(float x, float y) {
        float wx = (x - panX) / zoom, wy = (y - panY) / zoom;
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card k = cards.get(i);
            if (k.hidden) continue;
            if (wx >= k.x && wx <= k.x + k.w * k.s && wy >= k.y && wy <= k.y + k.h * k.s) return k;
        }
        return null;
    }

    boolean isWin(Card k) {
        return desk2 && k.type != APPT;
    }

    boolean inTitle(Card k, float y) {
        return ((y - panY) / zoom - k.y) / k.s < tb();
    }

    float handleX(Card k) {
        return k.x * zoom + panX + k.w * zoom * k.s - 6 * d;
    }

    float handleY(Card k) {
        return k.y * zoom + panY + k.h * zoom * k.s - 6 * d;
    }

    boolean nearHandle(Card k, float x, float y) {
        float dx = x - handleX(k), dy = y - handleY(k);
        return dx * dx + dy * dy <= 30 * d * 30 * d;
    }

    // ---------- жизненный цикл ----------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w <= 0 || h <= 0) return;
        String nt = (desk2 ? "D" : "C") + (w > h ? "L" : "P");
        if (!nt.equals(tag)) {
            saveCards();
            saveView();
            loadCards();
            loadView();
        }
        bd.build(getContext(), w, h);
    }

    @Override
    protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        removeCallbacks(tick);
        if (v == VISIBLE) {
            readStats();
            post(tick);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    static String upStr(long ms) {
        long m = ms / 60000;
        long h = m / 60;
        long dd = h / 24;
        if (dd > 0) return dd + "d " + (h % 24) + "h";
        return h + "h " + (m % 60) + "m";
    }

    void readStats() {
        Context c = getContext();
        long ramTot = 0, ramUsed = 0, dTot = 0, dUsed = 0;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            if (mi.totalMem > 0) {
                ramFrac = 1f - (float) mi.availMem / mi.totalMem;
                ramTot = mi.totalMem / 1048576L;
                ramUsed = (mi.totalMem - mi.availMem) / 1048576L;
            }
            StatFs s = new StatFs(Environment.getDataDirectory().getPath());
            if (s.getTotalBytes() > 0) {
                storFrac = 1f - (float) s.getAvailableBytes() / s.getTotalBytes();
                dTot = s.getTotalBytes() / 1073741824L;
                dUsed = (s.getTotalBytes() - s.getAvailableBytes()) / 1073741824L;
            }
            Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                int sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                batLvl = sc > 0 ? l * 100 / sc : l;
                int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                batChg = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            }
            is24 = DateFormat.is24HourFormat(c);
        } catch (Exception e) {
            // не критично
        }
        String model = String.valueOf(Build.MODEL).toLowerCase(Locale.ROOT).replace(' ', '-');
        termL = new String[]{
                "aura@" + model,
                "-------------------",
                "OS      Android " + Build.VERSION.RELEASE,
                "Device  " + Build.MANUFACTURER + " " + Build.MODEL,
                "Uptime  " + upStr(SystemClock.elapsedRealtime()),
                "RAM     " + ramUsed + " / " + ramTot + " MB",
                "Disk    " + dUsed + " / " + dTot + " GB",
                "Battery " + batLvl + "%" + (batChg ? " (charging)" : ""),
                "Shell   Aura 3.0"
        };
    }

    // ---------- рисование ----------

    @Override
    protected void onDraw(Canvas c) {
        boolean anim = false;
        if (scroller.computeScrollOffset()) {
            panX = scroller.getCurrX();
            panY = scroller.getCurrY();
            anim = true;
        }
        phase = (SystemClock.uptimeMillis() % 2000) / 2000f * (float) (Math.PI * 2);
        int W = getWidth(), H = getHeight();
        if (bd.full != null) c.drawBitmap(bd.full, 0, 0, null);
        else c.drawColor(0xFF101216);
        if (!desk2) dots(c, W, H);
        for (int i = 0; i < cards.size(); i++) {
            Card k = cards.get(i);
            if (k.hidden) continue;
            float sx = k.x * zoom + panX, sy = k.y * zoom + panY;
            if (sx > W || sy > H || sx + k.w * zoom * k.s < 0 || sy + k.h * zoom * k.s < 0) continue;
            anim |= card(c, k, sx, sy, W, H);
        }
        if (desk2) {
            topBar(c, W, H);
            dockBar(c, W, H);
        }
        // ручка изменения размера
        if (sel != null && !sel.hidden && cards.contains(sel)) {
            float hx = handleX(sel), hy = handleY(sel);
            p.setStyle(Paint.Style.FILL);
            p.setColor(bd.accent);
            c.drawCircle(hx, hy, 12 * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xFF14161A);
            c.drawLine(hx - 4 * d, hy - 4 * d, hx + 4 * d, hy + 4 * d, p);
            c.drawLine(hx + 4 * d, hy + 1 * d, hx + 4 * d, hy + 4 * d, p);
            c.drawLine(hx + 1 * d, hy + 4 * d, hx + 4 * d, hy + 4 * d, p);
            p.setStrokeCap(Paint.Cap.BUTT);
        }
        // корзина для иконок
        if ((mode == 2) && hit != null && hit.type == APPT) {
            boolean in = lastY > H - 120 * d;
            p.setStyle(Paint.Style.FILL);
            p.setColor(in ? 0xAAFF5A5A : 0x66FF5A5A);
            rf.set(W / 2f - 90 * d, H - 112 * d, W / 2f + 90 * d, H - 68 * d);
            c.drawRoundRect(rf, 22 * d, 22 * d, p);
            text(c, "Убрать с рабочего стола", W / 2f, H - 85 * d, 13, 0xFFFFFFFF, true, Paint.Align.CENTER);
        }
        // «ручка» панели приложений
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x80FFFFFF);
        rf.set(W / 2f - 28 * d, H - 30 * d, W / 2f + 28 * d, H - 25 * d);
        c.drawRoundRect(rf, 3 * d, 3 * d, p);
        if (anim) postInvalidateOnAnimation();
    }

    void dots(Canvas c, int W, int H) {
        float sp = 48 * d * zoom;
        if (sp < 28 * d) sp *= 2;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x30FFFFFF);
        float ox = ((panX % sp) + sp) % sp, oy = ((panY % sp) + sp) % sp;
        float r = 1.3f * d * Math.max(0.7f, zoom);
        for (float x = ox; x < W; x += sp)
            for (float y = oy; y < H; y += sp) c.drawCircle(x, y, r, p);
    }

    String title(Card k) {
        switch (k.type) {
            case TERM: return "~ neofetch";
            case CLOCK: return "clock";
            case INFO: return "calendar";
            case MUSIC: return "player";
            case STATS: return "system";
            case HELP: return "readme";
        }
        return "";
    }

    boolean card(Canvas c, Card k, float sx, float sy, int W, int H) {
        float ez = zoom * k.s;
        boolean win = isWin(k);
        float r = (win ? 12 : (k.type == APPT ? 20 : 28)) * d;
        c.save();
        c.translate(sx, sy);
        c.scale(ez, ez);
        rf.set(0, 0, k.w, k.h);
        clipP.reset();
        clipP.addRoundRect(rf, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clipP);
        if (bd.blur != null) {
            dst.set(-sx / ez, -sy / ez, (W - sx) / ez, (H - sy) / ez);
            c.drawBitmap(bd.blur, null, dst, bmpPaint);
        }
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(win ? 0x9E101216 : 0x8C121418);
        c.drawRect(rf, p);
        if (k.sheen == null)
            k.sheen = new LinearGradient(0, 0, 0, k.h * 0.6f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        p.setShader(k.sheen);
        c.drawRect(0, 0, k.w, k.h * 0.6f, p);
        p.setShader(null);
        if (win) {
            p.setColor(0x44000000);
            c.drawRect(0, 0, k.w, tb(), p);
            String t = title(k);
            tp.setTextSize(11 * d);
            tp.setTypeface(Typeface.MONOSPACE);
            float tw = tp.measureText(t);
            p.setColor(bd.accent);
            rf.set(8 * d, 4 * d, 8 * d + tw + 16 * d, tb() - 4 * d);
            c.drawRoundRect(rf, 7 * d, 7 * d, p);
            mono(c, t, 16 * d, tb() / 2 + 4 * d, 11, 0xFF14161A, true);
            for (int i = 0; i < 3; i++) {
                p.setColor(i == 2 ? 0xDDFF6B6B : 0x66FFFFFF);
                c.drawCircle(k.w - (14 + i * 15) * d, tb() / 2, 4 * d, p);
            }
        }
        c.restore();
        boolean selected = k == sel;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(selected ? 2 * d : d);
        p.setColor(selected ? bd.accent : 0x44FFFFFF);
        rf.set(0, 0, k.w, k.h);
        c.drawRoundRect(rf, r, r, p);

        c.save();
        float bh = k.h;
        if (win) {
            c.translate(0, tb());
            bh = k.h - tb();
        }
        boolean a = false;
        switch (k.type) {
            case CLOCK: clock(c, k.w, bh); break;
            case INFO: info(c, k.w, bh); break;
            case MUSIC: a = music(c, k.w, bh); break;
            case DOCK: dock(c, k.w, bh); break;
            case STATS: stats(c, k.w, bh); a = true; break;
            case HELP: help(c, k.w, bh); break;
            case TERM: term(c, k.w, bh); break;
            case APPT: appTile(c, k); break;
        }
        c.restore();
        p.setStrokeCap(Paint.Cap.BUTT);
        c.restore();
        return a;
    }

    void text(Canvas c, String s, float x, float y, float sp, int color, boolean bold, Paint.Align al) {
        tp.setTextSize(sp * d);
        tp.setColor(color);
        tp.setTypeface(bold ? tfBold : tfReg);
        tp.setTextAlign(al);
        c.drawText(s, x, y, tp);
    }

    void mono(Canvas c, String s, float x, float y, float sp, int color, boolean bold) {
        tp.setTextSize(sp * d);
        tp.setColor(color);
        tp.setTypeface(bold ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) : Typeface.MONOSPACE);
        tp.setTextAlign(Paint.Align.LEFT);
        c.drawText(s, x, y, tp);
    }

    String fit(String s, float maxW, float sp, boolean bold, boolean mono) {
        tp.setTextSize(sp * d);
        tp.setTypeface(mono ? Typeface.MONOSPACE : (bold ? tfBold : tfReg));
        if (tp.measureText(s) <= maxW) return s;
        int n = tp.breakText(s, true, maxW - tp.measureText("…"), null);
        return s.substring(0, Math.max(0, n)) + "…";
    }

    static String two(int v) {
        return (v < 10 ? "0" : "") + v;
    }

    static void seal(Path out, float cx, float cy, float R, int lobes, float amp) {
        out.reset();
        for (int i = 0; i <= 360; i++) {
            double t = i * Math.PI * 2 / 360;
            double r = R * (1 + amp * Math.cos(lobes * t));
            float x = (float) (cx + r * Math.cos(t)), y = (float) (cy + r * Math.sin(t));
            if (i == 0) out.moveTo(x, y);
            else out.lineTo(x, y);
        }
        out.close();
    }

    void clock(Canvas c, float w, float h) {
        float cx = w / 2, cy = h / 2;
        seal(sealP, cx, cy, 66 * d, 12, 0.055f);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x26FFFFFF);
        c.drawPath(sealP, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * d);
        p.setColor(0x66FFFFFF);
        c.drawPath(sealP, p);
        Calendar cal = Calendar.getInstance();
        int hh = cal.get(is24 ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
        if (!is24 && hh == 0) hh = 12;
        text(c, two(hh), cx, cy - 4 * d, 50, 0xFFFFFFFF, true, Paint.Align.CENTER);
        text(c, two(cal.get(Calendar.MINUTE)), cx, cy + 44 * d, 50, bd.accent, true, Paint.Align.CENTER);
    }

    void info(Canvas c, float w, float h) {
        Date now = new Date();
        String wd = new SimpleDateFormat("EEEE", Locale.getDefault()).format(now);
        String dt = new SimpleDateFormat("d MMMM", Locale.getDefault()).format(now);
        if (wd.length() > 0) wd = wd.substring(0, 1).toUpperCase() + wd.substring(1);
        float px = 18 * d;
        text(c, wd, px, 46 * d, 19, 0xFFFFFFFF, true, Paint.Align.LEFT);
        text(c, dt, px, 70 * d, 14, 0xB3FFFFFF, false, Paint.Align.LEFT);
        float bw = w - 36 * d, by = h - 40 * d;
        text(c, batLvl + "%", px, by - 12 * d, 18, 0xFFFFFFFF, true, Paint.Align.LEFT);
        text(c, batChg ? "заряжается" : "батарея", px + bw, by - 12 * d, 12, 0x99FFFFFF, false, Paint.Align.RIGHT);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x33FFFFFF);
        rf.set(px, by, px + bw, by + 8 * d);
        c.drawRoundRect(rf, 4 * d, 4 * d, p);
        p.setColor(bd.accent);
        rf.set(px, by, px + Math.max(8 * d, bw * batLvl / 100f), by + 8 * d);
        c.drawRoundRect(rf, 4 * d, 4 * d, p);
    }

    void term(Canvas c, float w, float h) {
        // логотип-«печать»
        float lx = 52 * d, ly = 64 * d;
        seal(sealP, lx, ly, 34 * d, 10, 0.07f);
        p.setStyle(Paint.Style.FILL);
        p.setColor((bd.accent & 0x00FFFFFF) | 0x44000000);
        c.drawPath(sealP, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * d);
        p.setColor(bd.accent);
        c.drawPath(sealP, p);
        text(c, "A", lx, ly + 10 * d, 30, bd.accent, true, Paint.Align.CENTER);

        float tx = 100 * d, maxW = w - tx - 10 * d;
        for (int i = 0; i < termL.length; i++) {
            String line = fit(termL[i], maxW, 10.5f, false, true);
            float y = (18 + i * 16) * d;
            if (i == 0) {
                mono(c, line, tx, y, 10.5f, bd.accent, true);
            } else if (i == 1) {
                mono(c, line, tx, y, 10.5f, 0x66FFFFFF, false);
            } else {
                int sp = line.indexOf(' ');
                if (sp > 0) {
                    mono(c, line.substring(0, sp), tx, y, 10.5f, bd.accent, true);
                    tp.setTextSize(10.5f * d);
                    tp.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                    float off = tp.measureText(line.substring(0, sp));
                    mono(c, line.substring(sp), tx + off, y, 10.5f, 0xE6FFFFFF, false);
                } else {
                    mono(c, line, tx, y, 10.5f, 0xE6FFFFFF, false);
                }
            }
        }
        int[] pal = {0xFFFF6B6B, 0xFFFFB86B, 0xFFF1E05A, 0xFF7BDC8C, 0xFF6BC8FF, 0xFFB49BFF, 0xFFFF8EC8, 0xFFE6E6E6};
        float py = (18 + termL.length * 16) * d;
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < pal.length; i++) {
            p.setColor(pal[i]);
            rf.set(tx + i * 16 * d, py, tx + i * 16 * d + 13 * d, py + 11 * d);
            c.drawRoundRect(rf, 2 * d, 2 * d, p);
        }
        boolean on = (System.currentTimeMillis() / 500) % 2 == 0;
        mono(c, "$ " + (on ? "█" : ""), 14 * d, h - 12 * d, 11, 0xE6FFFFFF, false);
    }

    float waveX0() {
        return 112 * d;
    }

    float waveX1(float w) {
        return w - 20 * d;
    }

    float waveY() {
        return 92 * d;
    }

    boolean music(Canvas c, float w, float h) {
        float acx = 60 * d, acy = 56 * d, ar = 38 * d;
        seal(sealP, acx, acy, ar, 10, 0.07f);
        c.save();
        c.clipPath(sealP);
        if (music.art != null) {
            Bitmap a = music.art;
            int s = Math.min(a.getWidth(), a.getHeight());
            srcR.set((a.getWidth() - s) / 2, (a.getHeight() - s) / 2, (a.getWidth() + s) / 2, (a.getHeight() + s) / 2);
            dst.set(acx - ar * 1.1f, acy - ar * 1.1f, acx + ar * 1.1f, acy + ar * 1.1f);
            c.drawBitmap(a, srcR, dst, bmpPaint);
        } else {
            p.setStyle(Paint.Style.FILL);
            p.setColor((bd.accent & 0x00FFFFFF) | 0x55000000);
            c.drawRect(acx - ar, acy - ar, acx + ar, acy + ar, p);
            text(c, "♪", acx, acy + 12 * d, 34, 0xCCFFFFFF, true, Paint.Align.CENTER);
        }
        c.restore();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * d);
        p.setColor(0x66FFFFFF);
        c.drawPath(sealP, p);

        String t, a;
        if (!music.hasAccess) {
            t = "Нужен доступ к уведомлениям";
            a = "Нажми, чтобы включить";
        } else if (!music.hasSession || music.title.length() == 0) {
            t = "Ничего не играет";
            a = "Запусти музыку в любом плеере";
        } else {
            t = music.title;
            a = music.artist.length() == 0 ? "—" : music.artist;
        }
        float tx = 112 * d, maxW = w - tx - 16 * d;
        text(c, fit(t, maxW, 17, true, false), tx, 40 * d, 17, 0xFFFFFFFF, true, Paint.Align.LEFT);
        text(c, fit(a, maxW, 13, false, false), tx, 62 * d, 13, 0xB3FFFFFF, false, Paint.Align.LEFT);

        // волнистый прогресс
        float x0 = waveX0(), x1 = waveX1(w), wy = waveY();
        float pr = (music.hasSession && music.duration > 0) ? Math.min(1f, music.position() / (float) music.duration) : 0f;
        float xe = x0 + (x1 - x0) * pr;
        float target = music.playing ? 4.2f * d : 0f;
        waveAmp += (target - waveAmp) * 0.12f;
        boolean anim = music.playing || Math.abs(target - waveAmp) > 0.05f * d;
        float ph = (SystemClock.uptimeMillis() % 1600) / 1600f * (float) (Math.PI * 2);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(4 * d);
        p.setColor(0x40FFFFFF);
        if (x1 > xe + 10 * d) c.drawLine(xe + 10 * d, wy, x1, wy, p);

        float wl = 26 * d, xs = xe - 6 * d;
        if (xs > x0 + 2 * d) {
            tmpP.reset();
            boolean first = true;
            for (float x = x0; ; x += 1.5f * d) {
                boolean last = x >= xs;
                if (last) x = xs;
                float env = Math.min(1f, (x - x0) / (14 * d));
                float y = wy + waveAmp * env * (float) Math.sin((x - x0) / wl * 2 * Math.PI - ph);
                if (first) {
                    tmpP.moveTo(x, y);
                    first = false;
                } else {
                    tmpP.lineTo(x, y);
                }
                if (last) break;
            }
            p.setStrokeWidth(5 * d);
            p.setColor(bd.accent);
            c.drawPath(tmpP, p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(bd.accent);
        rf.set(xe - 2.5f * d, wy - 11 * d, xe + 2.5f * d, wy + 11 * d);
        c.drawRoundRect(rf, 2.5f * d, 2.5f * d, p);

        if (music.hasSession && music.duration > 0)
            text(c, mmss(music.position()) + " / " + mmss(music.duration), 18 * d, 132 * d, 12, 0x99FFFFFF, false, Paint.Align.LEFT);

        float by = btnY(h);
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 3; i++) {
            float bx = btnX(w, i);
            p.setColor(i == 1 ? bd.accent : 0x2EFFFFFF);
            c.drawCircle(bx, by, (i == 1 ? 22 : 18) * d, p);
            int kind = i == 0 ? 0 : (i == 2 ? 2 : (music.playing ? 3 : 1));
            ctrlIcon(c, kind, bx, by, 7 * d, i == 1 ? 0xFF14161A : 0xFFFFFFFF);
        }
        return anim;
    }

    float btnY(float h) {
        return h - 34 * d;
    }

    float btnX(float w, int i) {
        return w - 38 * d - (2 - i) * 48 * d;
    }

    static String mmss(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + two((int) (s % 60));
    }

    void ctrlIcon(Canvas c, int kind, float cx, float cy, float s, int color) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        tmpP.reset();
        if (kind == 0) {
            c.drawRect(cx - s, cy - s, cx - s + 2.5f * d, cy + s, p);
            tmpP.moveTo(cx + s, cy - s);
            tmpP.lineTo(cx + s, cy + s);
            tmpP.lineTo(cx - s + 3 * d, cy);
            tmpP.close();
        } else if (kind == 2) {
            c.drawRect(cx + s - 2.5f * d, cy - s, cx + s, cy + s, p);
            tmpP.moveTo(cx - s, cy - s);
            tmpP.lineTo(cx - s, cy + s);
            tmpP.lineTo(cx + s - 3 * d, cy);
            tmpP.close();
        } else if (kind == 1) {
            tmpP.moveTo(cx - s * 0.8f, cy - s);
            tmpP.lineTo(cx - s * 0.8f, cy + s);
            tmpP.lineTo(cx + s, cy);
            tmpP.close();
        } else {
            c.drawRect(cx - s * 0.7f, cy - s, cx - s * 0.2f, cy + s, p);
            c.drawRect(cx + s * 0.2f, cy - s, cx + s * 0.7f, cy + s, p);
        }
        c.drawPath(tmpP, p);
    }

    void dock(Canvas c, float w, float h) {
        List<AppRepo.App> pins = host.pinned();
        if (pins.isEmpty()) {
            text(c, "Зажми приложение в списке — оно появится здесь", w / 2, h / 2 + 5 * d, 13, 0x99FFFFFF, false, Paint.Align.CENTER);
            return;
        }
        float slot = w / 4;
        for (int i = 0; i < pins.size() && i < 4; i++) {
            Bitmap b = pins.get(i).icon;
            if (b == null) continue;
            float cx = slot * i + slot / 2;
            dst.set(cx - 26 * d, h / 2 - 26 * d, cx + 26 * d, h / 2 + 26 * d);
            c.drawBitmap(b, null, dst, bmpPaint);
        }
    }

    void appTile(Canvas c, Card k) {
        AppRepo.App a = host.appOf(k.pkg);
        if (a == null || a.icon == null) return;
        dst.set(k.w / 2 - 26 * d, k.h / 2 - 26 * d, k.w / 2 + 26 * d, k.h / 2 + 26 * d);
        c.drawBitmap(a.icon, null, dst, bmpPaint);
    }

    void stats(Canvas c, float w, float h) {
        gauge(c, w * 0.27f, 76 * d, 48 * d, ramFrac, "ОЗУ");
        gauge(c, w * 0.73f, 76 * d, 48 * d, storFrac, "Память");
    }

    void gauge(Canvas c, float cx, float cy, float r, float frac, String label) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(8 * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0x22FFFFFF);
        c.drawCircle(cx, cy, r, p);
        int n = Math.max(2, (int) (frac * 140));
        tmpP.reset();
        for (int i = 0; i <= n; i++) {
            double t = -Math.PI / 2 + 2 * Math.PI * frac * i / n;
            double rr = r + 2.2 * d * Math.sin(14 * t + phase);
            float x = (float) (cx + rr * Math.cos(t)), y = (float) (cy + rr * Math.sin(t));
            if (i == 0) tmpP.moveTo(x, y);
            else tmpP.lineTo(x, y);
        }
        p.setColor(bd.accent);
        c.drawPath(tmpP, p);
        text(c, Math.round(frac * 100) + "%", cx, cy + 8 * d, 22, 0xFFFFFFFF, true, Paint.Align.CENTER);
        text(c, label, cx, cy + r + 28 * d, 12, 0x99FFFFFF, false, Paint.Align.CENTER);
    }

    void help(Canvas c, float w, float h) {
        String[] l = {
                "Как пользоваться",
                "• Двигай холст пальцем, зум — щипком",
                "• Долгое нажатие на карточку — выбрать",
                "• У выбранной тяни за уголок — размер",
                "• Долгое нажатие на пустом месте — меню",
                "• Свайп вверх снизу — все приложения",
                "• Двойной тап — вернуться в начало"
        };
        for (int i = 0; i < l.length; i++)
            text(c, l[i], 18 * d, (36 + i * 22) * d, i == 0 ? 15 : 12.5f, i == 0 ? 0xFFFFFFFF : 0xCCFFFFFF, i == 0, Paint.Align.LEFT);
    }

    // ---------- вид 2: панель и док ----------

    float topInset() {
        if (getWidth() > getHeight()) return 0f;
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 24 * d;
    }

    void glassRect(Canvas c, float l, float t, float r, float b, float rad, int W, int H) {
        rf.set(l, t, r, b);
        clipP.reset();
        clipP.addRoundRect(rf, rad, rad, Path.Direction.CW);
        c.save();
        c.clipPath(clipP);
        if (bd.blur != null) {
            dst.set(0, 0, W, H);
            c.drawBitmap(bd.blur, null, dst, bmpPaint);
        }
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(0xA0101216);
        c.drawRect(rf, p);
        c.restore();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(d);
        p.setColor(0x44FFFFFF);
        c.drawRoundRect(rf, rad, rad, p);
    }

    void topBar(Canvas c, int W, int H) {
        float y0 = topInset() + 6 * d, y1 = y0 + 26 * d;
        glassRect(c, 8 * d, y0, W - 8 * d, y1, 13 * d, W, H);
        float cy = (y0 + y1) / 2;
        p.setStyle(Paint.Style.FILL);
        float x = 20 * d;
        for (int i = 0; i < 5; i++) {
            if (i == 0) {
                p.setColor(bd.accent);
                rf.set(x, cy - 3.5f * d, x + 20 * d, cy + 3.5f * d);
                c.drawRoundRect(rf, 3.5f * d, 3.5f * d, p);
                x += 28 * d;
            } else {
                p.setColor(0x66FFFFFF);
                c.drawCircle(x, cy, 3.2f * d, p);
                x += 14 * d;
            }
        }
        Calendar cal = Calendar.getInstance();
        int hh = cal.get(is24 ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
        if (!is24 && hh == 0) hh = 12;
        String tm = two(hh) + ":" + two(cal.get(Calendar.MINUTE));
        tp.setTextSize(12 * d);
        tp.setTypeface(tfBold);
        float tw = tp.measureText(tm);
        p.setColor(0x33FFFFFF);
        rf.set(W / 2f - tw / 2 - 12 * d, cy - 10 * d, W / 2f + tw / 2 + 12 * d, cy + 10 * d);
        c.drawRoundRect(rf, 10 * d, 10 * d, p);
        text(c, tm, W / 2f, cy + 4.5f * d, 12, 0xFFFFFFFF, true, Paint.Align.CENTER);
        String st = "RAM " + Math.round(ramFrac * 100) + "%   BAT " + batLvl + "%";
        text(c, st, W - 20 * d, cy + 4 * d, 11, 0xCCFFFFFF, false, Paint.Align.RIGHT);
    }

    float[] dockRect(int W, int H, int n) {
        float slot = 56 * d;
        float w = (n + 1) * slot + 16 * d;
        float l = (W - w) / 2f, b = H - 40 * d;
        return new float[]{l, b - 62 * d, l + w, b, slot};
    }

    void dockBar(Canvas c, int W, int H) {
        List<AppRepo.App> pins = host.pinned();
        int n = Math.min(4, pins.size());
        float[] g = dockRect(W, H, n);
        glassRect(c, g[0], g[1], g[2], g[3], 26 * d, W, H);
        float cy = (g[1] + g[3]) / 2;
        for (int i = 0; i < n; i++) {
            Bitmap b = pins.get(i).icon;
            if (b == null) continue;
            float cx = g[0] + 8 * d + g[4] * i + g[4] / 2;
            dst.set(cx - 22 * d, cy - 22 * d, cx + 22 * d, cy + 22 * d);
            c.drawBitmap(b, null, dst, bmpPaint);
        }
        float cx = g[0] + 8 * d + g[4] * n + g[4] / 2;
        p.setStyle(Paint.Style.FILL);
        p.setColor(bd.accent);
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) c.drawCircle(cx + (i - 1) * 9 * d, cy + (j - 1) * 9 * d, 2.6f * d, p);
    }

    /** -2 — мимо дока, -1 — кнопка «все приложения», иначе номер закреплённого приложения. */
    int dockHit(float x, float y, int W, int H) {
        int n = Math.min(4, host.pinned().size());
        float[] g = dockRect(W, H, n);
        if (x < g[0] || x > g[2] || y < g[1] || y > g[3]) return -2;
        int i = (int) ((x - g[0] - 8 * d) / g[4]);
        if (i < 0) i = 0;
        return i >= n ? -1 : i;
    }

    // ---------- касания ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!desk2) sgd.onTouchEvent(e);
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                if (panAnim != null) panAnim.cancel();
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                downX = lastX = x;
                downY = lastY = y;
                mode = 0;
                longFired = false;
                hit = null;
                if (sel != null && !sel.hidden && nearHandle(sel, x, y)) {
                    mode = 5;
                    startS = sel.s;
                    float tx = sel.x * zoom + panX, ty = sel.y * zoom + panY;
                    startDist = Math.max(1f, (float) Math.hypot(x - tx, y - ty));
                    return true;
                }
                if (desk2 && dockHit(x, y, getWidth(), getHeight()) != -2) return true;
                hit = hitCard(x, y);
                if (desk2 && hit != null) {
                    cards.remove(hit);
                    cards.add(hit);
                    sel = hit;
                    invalidate();
                }
                postDelayed(longPress, 420);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                removeCallbacks(longPress);
                if (mode == 2) saveCards();
                mode = desk2 ? 3 : 4;
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (mode == 4 && e.getPointerCount() == 2) {
                    int rem = e.getActionIndex() == 0 ? 1 : 0;
                    lastX = e.getX(rem);
                    lastY = e.getY(rem);
                    if (vt != null) vt.clear();
                    mode = 1;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (vt != null) vt.addMovement(e);
                if (mode == 4) return true;
                if (mode == 5 && sel != null) {
                    float tx = sel.x * zoom + panX, ty = sel.y * zoom + panY;
                    float dist = (float) Math.hypot(x - tx, y - ty);
                    sel.s = Math.max(SMIN, Math.min(SMAX, startS * dist / startDist));
                    invalidate();
                    return true;
                }
                if (mode == 0 && (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop)) {
                    removeCallbacks(longPress);
                    if (downY > getHeight() - (desk2 ? 56 : 90) * d && (downY - y) > Math.abs(x - downX)) {
                        mode = 3;
                        host.openDrawer();
                    } else if (desk2) {
                        mode = (hit != null && hit.type != APPT && inTitle(hit, downY)) ? 2 : 3;
                    } else {
                        mode = 1;
                    }
                }
                if (mode == 1) {
                    panX += x - lastX;
                    panY += y - lastY;
                    invalidate();
                } else if (mode == 2 && hit != null) {
                    hit.x += (x - lastX) / zoom;
                    hit.y += (y - lastY) / zoom;
                    invalidate();
                }
                lastX = x;
                lastY = y;
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (vt != null) vt.addMovement(e);
                if (mode == 0 && !longFired) {
                    tap(x, y);
                } else if (mode == 1 && vt != null) {
                    vt.computeCurrentVelocity(1000, maxFling);
                    float vx = vt.getXVelocity(), vy = vt.getYVelocity();
                    if (Math.abs(vx) > minFling || Math.abs(vy) > minFling) {
                        scroller.fling((int) panX, (int) panY, (int) vx, (int) vy, -BIG, BIG, -BIG, BIG);
                        invalidate();
                    }
                } else if (mode == 2) {
                    if (hit != null && hit.type == APPT && y > getHeight() - 120 * d) {
                        cards.remove(hit);
                        if (sel == hit) sel = null;
                    }
                    saveCards();
                } else if (mode == 5) {
                    saveCards();
                }
                finishTouch();
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                if (mode == 2 || mode == 5) saveCards();
                finishTouch();
                return true;
        }
        return true;
    }

    void finishTouch() {
        mode = 3;
        hit = null;
        if (vt != null) {
            vt.recycle();
            vt = null;
        }
        saveView();
        invalidate();
    }

    void tap(float x, float y) {
        int W = getWidth(), H = getHeight();
        if (desk2) {
            int dh = dockHit(x, y, W, H);
            if (dh != -2) {
                sel = null;
                invalidate();
                if (dh == -1) {
                    host.openDrawer();
                } else {
                    List<AppRepo.App> pins = host.pinned();
                    if (dh < pins.size()) {
                        float[] g = dockRect(W, H, Math.min(4, pins.size()));
                        float cx = g[0] + 8 * d + g[4] * dh + g[4] / 2, cy = (g[1] + g[3]) / 2;
                        host.launch(pins.get(dh), cx - 22 * d, cy - 22 * d, 44 * d, 44 * d);
                    }
                }
                return;
            }
        }
        Card k = hitCard(x, y);
        if (!desk2 && sel != null) {
            Card was = sel;
            sel = null;
            invalidate();
            if (k == was) return;
        }
        if (desk2 && k == null && sel != null) {
            sel = null;
            invalidate();
        }
        if (k == null) {
            if (y > H - 80 * d && Math.abs(x - W / 2f) < 90 * d) {
                host.openDrawer();
                return;
            }
            if (!desk2) {
                long t = SystemClock.uptimeMillis();
                if (t - lastTap < 300) recenter();
                lastTap = t;
            }
            return;
        }
        float ez = zoom * k.s;
        float sx = k.x * zoom + panX, sy = k.y * zoom + panY;
        float lx = ((x - panX) / zoom - k.x) / k.s, ly = ((y - panY) / zoom - k.y) / k.s;
        boolean win = isWin(k);
        if (win) {
            if (ly < tb()) {
                if (lx > k.w - 26 * d) {
                    k.hidden = true;
                    if (sel == k) sel = null;
                    saveCards();
                    invalidate();
                }
                return;
            }
            ly -= tb();
        }
        float bh = win ? k.h - tb() : k.h;
        if (k.type == MUSIC) {
            if (!music.hasAccess) {
                host.askAccess();
                return;
            }
            for (int i = 0; i < 3; i++) {
                float dx = lx - btnX(k.w, i), dy = ly - btnY(bh);
                if (dx * dx + dy * dy <= 26 * d * 26 * d) {
                    if (i == 0) music.prev();
                    else if (i == 1) music.toggle();
                    else music.next();
                    return;
                }
            }
            float x0 = waveX0(), x1 = waveX1(k.w);
            if (Math.abs(ly - waveY()) <= 14 * d && lx >= x0 - 6 * d && lx <= x1 + 6 * d)
                music.seekTo(Math.max(0f, Math.min(1f, (lx - x0) / (x1 - x0))));
        } else if (k.type == DOCK) {
            List<AppRepo.App> pins = host.pinned();
            float slot = k.w / 4;
            int i = (int) (lx / slot);
            if (i >= 0 && i < pins.size() && i < 4) {
                float cx = sx + (slot * i + slot / 2) * ez, cy = sy + (k.h / 2) * ez, hf = 26 * d * ez;
                host.launch(pins.get(i), cx - hf, cy - hf, hf * 2, hf * 2);
            }
        } else if (k.type == APPT) {
            AppRepo.App a = host.appOf(k.pkg);
            if (a != null) {
                float hf = 26 * d * ez;
                float cx = sx + k.w / 2 * ez, cy = sy + k.h / 2 * ez;
                host.launch(a, cx - hf, cy - hf, hf * 2, hf * 2);
            }
        }
    }
}
