package com.aura.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AppRepo {
    public static class App {
        public String label, pkg, cls;
        public Bitmap icon;
    }

    public static List<App> load(Context c, int px) {
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> l = pm.queryIntentActivities(i, 0);
        ArrayList<App> out = new ArrayList<App>();
        for (ResolveInfo ri : l) {
            if (ri.activityInfo == null || c.getPackageName().equals(ri.activityInfo.packageName)) continue;
            App a = new App();
            a.pkg = ri.activityInfo.packageName;
            a.cls = ri.activityInfo.name;
            a.label = String.valueOf(ri.loadLabel(pm));
            try {
                a.icon = toBitmap(ri.loadIcon(pm), px);
            } catch (Throwable t) {
                a.icon = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            }
            out.add(a);
        }
        final Collator col = Collator.getInstance();
        Collections.sort(out, new Comparator<App>() {
            public int compare(App x, App y) {
                return col.compare(x.label, y.label);
            }
        });
        return out;
    }

    static Bitmap toBitmap(Drawable d, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, px, px);
        d.draw(c);
        return b;
    }

    /** Приложения для дока по умолчанию: телефон, браузер, камера, настройки. */
    public static List<String> defaultPins(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent[] probes = new Intent[]{
                new Intent(Intent.ACTION_DIAL),
                new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com")),
                new Intent("android.media.action.IMAGE_CAPTURE"),
                new Intent(Settings.ACTION_SETTINGS)
        };
        ArrayList<String> r = new ArrayList<String>();
        for (Intent in : probes) {
            ResolveInfo ri = pm.resolveActivity(in, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) {
                String p = ri.activityInfo.packageName;
                if (!"android".equals(p) && !r.contains(p)) r.add(p);
            }
        }
        return r;
    }
}
