package com.aura.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements CanvasView.Host {
    static final int REQ_WALLPAPER = 11;

    CanvasView canvas;
    LaunchOverlay overlay;
    FrameLayout root, drawer;
    LinearLayout drawerCol;
    GridView grid;
    EditText search;
    AppAdapter adapter;
    NowPlaying music;
    SharedPreferences sp;
    boolean drawerOpen;
    List<AppRepo.App> pinnedCache;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ArrayList<AppRepo.App> all = new ArrayList<AppRepo.App>();
    final ArrayList<AppRepo.App> shown = new ArrayList<AppRepo.App>();
    final HashMap<String, AppRepo.App> byPkg = new HashMap<String, AppRepo.App>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("aura", 0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            // рисуем под вырезом камеры (пунчхолом) в любой ориентации
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        applySystemUi();

        music = new NowPlaying(this);
        root = new FrameLayout(this);
        canvas = new CanvasView(this, this, music);
        root.addView(canvas, new FrameLayout.LayoutParams(-1, -1));
        buildDrawer();
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        overlay = new LaunchOverlay(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        music.listener = new NowPlaying.Listener() {
            public void onMusic() {
                canvas.invalidate();
            }
        };
        loadApps();
    }

    /** В вертикальном режиме — обычный полноэкранный вид, в горизонтальном — полный иммерсивный режим. */
    void applySystemUi() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (land) {
            f |= View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        getWindow().getDecorView().setSystemUiVisibility(f);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applySystemUi();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        boolean land = c.orientation == Configuration.ORIENTATION_LANDSCAPE;
        grid.setNumColumns(land ? 8 : 4);
        drawerCol.setPadding(dp(16), dp(land ? 20 : 52), dp(16), dp(20));
        applySystemUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        music.start();
        loadApps();
        overlay.close();
    }

    @Override
    protected void onStop() {
        music.stop();
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        overlay.close();
        if (i != null && Intent.ACTION_MAIN.equals(i.getAction())) {
            if (drawerOpen) closeDrawer();
            else canvas.recenter();
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) closeDrawer();
        else canvas.recenter();
    }

    int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------- приложения ----------

    void loadApps() {
        new Thread(new Runnable() {
            public void run() {
                final List<AppRepo.App> l = AppRepo.load(MainActivity.this, dp(80));
                ui.post(new Runnable() {
                    public void run() {
                        all.clear();
                        all.addAll(l);
                        byPkg.clear();
                        for (AppRepo.App a : l) if (!byPkg.containsKey(a.pkg)) byPkg.put(a.pkg, a);
                        pinnedCache = null;
                        filter(search.getText().toString());
                        canvas.invalidate();
                    }
                });
            }
        }).start();
    }

    void filter(String q) {
        shown.clear();
        String s = q.trim().toLowerCase(Locale.getDefault());
        for (AppRepo.App a : all)
            if (s.length() == 0 || a.label.toLowerCase(Locale.getDefault()).contains(s)) shown.add(a);
        adapter.notifyDataSetChanged();
    }

    List<String> pins() {
        String s = sp.getString("pins", null);
        if (s == null) {
            s = TextUtils.join(",", AppRepo.defaultPins(this));
            sp.edit().putString("pins", s).apply();
        }
        ArrayList<String> r = new ArrayList<String>();
        for (String x : s.split(",")) if (x.length() > 0) r.add(x);
        return r;
    }

    public List<AppRepo.App> pinned() {
        if (pinnedCache == null) {
            ArrayList<AppRepo.App> r = new ArrayList<AppRepo.App>();
            for (String pkg : pins()) {
                AppRepo.App a = byPkg.get(pkg);
                if (a != null) r.add(a);
            }
            pinnedCache = r;
        }
        return pinnedCache;
    }

    public AppRepo.App appOf(String pkg) {
        return byPkg.get(pkg);
    }

    void togglePin(AppRepo.App a) {
        ArrayList<String> p = new ArrayList<String>(pins());
        if (p.contains(a.pkg)) {
            p.remove(a.pkg);
            toast("Откреплено из дока");
        } else {
            if (p.size() >= 4) {
                toast("В доке уже 4 приложения — сначала открепи одно");
                return;
            }
            p.add(a.pkg);
            toast("Закреплено в доке");
        }
        sp.edit().putString("pins", TextUtils.join(",", p)).apply();
        pinnedCache = null;
        canvas.invalidate();
    }

    /** Запуск: на виде 1 — своя анимация «окно растёт из иконки», на виде 2 — системная. */
    public void launch(final AppRepo.App a, float x, float y, float w, float h) {
        if (drawerOpen) closeDrawer();
        if (!canvas.desk2) {
            overlay.open(new RectF(x, y, x + w, y + h), a.icon, canvas.bd.accent, new Runnable() {
                public void run() {
                    startApp(a, null);
                }
            });
        } else {
            Bundle o = null;
            try {
                o = ActivityOptions.makeScaleUpAnimation(canvas, (int) x, (int) y, Math.max(1, (int) w), Math.max(1, (int) h)).toBundle();
            } catch (Exception e) {
                o = null;
            }
            startApp(a, o);
        }
    }

    void startApp(AppRepo.App a, Bundle opts) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setClassName(a.pkg, a.cls);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (opts == null)
                opts = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle();
            startActivity(i, opts);
        } catch (Exception e) {
            overlay.close();
            toast("Не удалось открыть приложение");
        }
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------- список приложений ----------

    void buildDrawer() {
        drawer = new FrameLayout(this);
        drawer.setVisibility(View.GONE);
        drawer.setClickable(true);

        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        drawerCol = new LinearLayout(this);
        drawerCol.setOrientation(LinearLayout.VERTICAL);
        drawerCol.setPadding(dp(16), dp(land ? 20 : 52), dp(16), dp(20));

        search = new EditText(this);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0x26FFFFFF);
        gd.setCornerRadius(dp(26));
        search.setBackground(gd);
        search.setHint("Поиск приложений");
        search.setHintTextColor(0x99FFFFFF);
        search.setTextColor(0xFFFFFFFF);
        search.setTextSize(16);
        search.setSingleLine(true);
        search.setPadding(dp(20), dp(12), dp(20), dp(12));
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
                filter(s.toString());
            }

            public void afterTextChanged(Editable e) {
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, KeyEvent ev) {
                if (!shown.isEmpty()) {
                    launch(shown.get(0), drawer.getWidth() / 2f - dp(28), drawer.getHeight() / 2f - dp(28), dp(56), dp(56));
                }
                return true;
            }
        });

        adapter = new AppAdapter();
        grid = new GridView(this);
        grid.setNumColumns(land ? 8 : 4);
        grid.setVerticalSpacing(dp(4));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new ColorDrawable(0));
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(12), 0, dp(24));
        grid.setVerticalScrollBarEnabled(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                View icon = v instanceof ViewGroup ? ((ViewGroup) v).getChildAt(0) : v;
                int[] loc = new int[2];
                icon.getLocationOnScreen(loc);
                launch(shown.get(pos), loc[0], loc[1], icon.getWidth(), icon.getHeight());
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                appMenu(shown.get(pos));
                return true;
            }
        });

        drawerCol.addView(search, new LinearLayout.LayoutParams(-1, -2));
        drawerCol.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        drawer.addView(drawerCol, new FrameLayout.LayoutParams(-1, -1));
    }

    public void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        if (canvas.bd.blur != null) {
            BitmapDrawable bdw = new BitmapDrawable(getResources(), canvas.bd.blur);
            bdw.setFilterBitmap(true);
            drawer.setBackground(new LayerDrawable(new Drawable[]{bdw, new ColorDrawable(0xB8101216)}));
        } else {
            drawer.setBackground(new ColorDrawable(0xF0101216));
        }
        search.setText("");
        filter("");
        grid.setSelection(0);
        drawer.setVisibility(View.VISIBLE);
        drawer.setAlpha(0f);
        drawer.setTranslationY(dp(60));
        drawer.animate().alpha(1f).translationY(0f).setDuration(220).start();
    }

    void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
        drawer.animate().alpha(0f).translationY(dp(60)).setDuration(180).withEndAction(new Runnable() {
            public void run() {
                if (!drawerOpen) drawer.setVisibility(View.GONE);
            }
        }).start();
    }

    class AppAdapter extends BaseAdapter {
        public int getCount() {
            return shown.size();
        }

        public Object getItem(int i) {
            return shown.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout row;
            if (cv == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                row.setPadding(0, dp(8), 0, dp(8));
                ImageView iv = new ImageView(MainActivity.this);
                row.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(56)));
                TextView tv = new TextView(MainActivity.this);
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(12);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setGravity(Gravity.CENTER);
                tv.setPadding(dp(2), dp(6), dp(2), 0);
                row.addView(tv, new LinearLayout.LayoutParams(-1, -2));
            } else {
                row = (LinearLayout) cv;
            }
            AppRepo.App a = shown.get(pos);
            ((ImageView) row.getChildAt(0)).setImageBitmap(a.icon);
            ((TextView) row.getChildAt(1)).setText(a.label);
            return row;
        }
    }

    // ---------- меню ----------

    void appMenu(final AppRepo.App a) {
        String pinLabel = pins().contains(a.pkg) ? "Убрать из дока" : "Закрепить в доке";
        Glass.menu(this, canvas.bd, a.label, new String[]{pinLabel, "Добавить на рабочий стол"}, new Glass.Pick() {
            public void on(int which) {
                if (which == 0) {
                    togglePin(a);
                } else if (canvas.addAppTile(a.pkg)) {
                    closeDrawer();
                    toast("Добавлено на рабочий стол");
                } else {
                    toast("Это приложение уже на рабочем столе");
                }
            }
        });
    }

    public void showMenu() {
        final ArrayList<String> names = new ArrayList<String>();
        final ArrayList<Runnable> acts = new ArrayList<Runnable>();
        names.add(canvas.desk2 ? "Первый вид холста" : "Второй вид холста");
        acts.add(new Runnable() {
            public void run() {
                canvas.setDesk2(!canvas.desk2);
            }
        });
        names.add("Сменить обои");
        acts.add(new Runnable() {
            public void run() {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startActivityForResult(i, REQ_WALLPAPER);
            }
        });
        if (canvas.desk2) {
            names.add("Показать скрытые окна");
            acts.add(new Runnable() {
                public void run() {
                    canvas.showAllWindows();
                }
            });
        }
        names.add("Сбросить расположение");
        acts.add(new Runnable() {
            public void run() {
                canvas.resetLayout();
            }
        });
        names.add("Доступ к уведомлениям (плеер)");
        acts.add(new Runnable() {
            public void run() {
                askAccess();
            }
        });
        names.add("Сделать лаунчером по умолчанию");
        acts.add(new Runnable() {
            public void run() {
                open(Settings.ACTION_HOME_SETTINGS);
            }
        });
        names.add("Настройки Android");
        acts.add(new Runnable() {
            public void run() {
                open(Settings.ACTION_SETTINGS);
            }
        });
        Glass.menu(this, canvas.bd, "Aura", names.toArray(new String[names.size()]), new Glass.Pick() {
            public void on(int which) {
                acts.get(which).run();
            }
        });
    }

    void open(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception e2) {
                toast("Не удалось открыть настройки");
            }
        }
    }

    public void askAccess() {
        open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        Toast.makeText(this, "Включи доступ для Aura. Если пункт серый: Настройки → Приложения → Aura → ⋮ → Разрешить ограниченные настройки",
                Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_WALLPAPER && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                Uri u = data.getData();
                InputStream in = getContentResolver().openInputStream(u);
                FileOutputStream out = openFileOutput("wallpaper.jpg", MODE_PRIVATE);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                canvas.reloadBackdrop();
            } catch (Exception e) {
                toast("Не удалось загрузить картинку");
            }
        }
    }
}
