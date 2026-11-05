package com.aura.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.List;

/** Читает текущий трек из активных медиа-сессий (нужен доступ к уведомлениям). */
public class NowPlaying {
    public interface Listener {
        void onMusic();
    }

    public String title = "", artist = "";
    public Bitmap art;
    public boolean playing, hasSession, hasAccess;
    public long duration;
    public Listener listener;

    private long posBase, posTime;
    private float speed = 1f;
    private final Context ctx;
    private final ComponentName cn;
    private MediaSessionManager msm;
    private MediaController ctl;

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata m) {
            readMeta(m);
            fire();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            readState(s);
            fire();
        }

        @Override
        public void onSessionDestroyed() {
            refresh();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sl =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> l) {
                    pick(l);
                    fire();
                }
            };

    public NowPlaying(Context c) {
        ctx = c.getApplicationContext();
        cn = new ComponentName(ctx, MediaListenerService.class);
    }

    private void fire() {
        if (listener != null) listener.onMusic();
    }

    public void start() {
        String s = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        hasAccess = s != null && s.contains(ctx.getPackageName());
        if (hasAccess) {
            try {
                msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
                msm.addOnActiveSessionsChangedListener(sl, cn);
                refresh();
                return;
            } catch (Exception e) {
                hasAccess = false;
            }
        }
        fire();
    }

    public void stop() {
        try {
            if (msm != null) msm.removeOnActiveSessionsChangedListener(sl);
        } catch (Exception e) {
            // ignore
        }
        detach();
    }

    private void detach() {
        if (ctl != null) {
            try {
                ctl.unregisterCallback(cb);
            } catch (Exception e) {
                // ignore
            }
            ctl = null;
        }
    }

    private void refresh() {
        try {
            pick(msm.getActiveSessions(cn));
        } catch (Exception e) {
            pick(null);
        }
        fire();
    }

    private void pick(List<MediaController> l) {
        MediaController best = null;
        if (l != null) {
            for (MediaController c : l) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
                if (best == null) best = c;
            }
        }
        if (best == null) {
            detach();
            hasSession = false;
            title = "";
            artist = "";
            art = null;
            playing = false;
            duration = 0;
            return;
        }
        if (ctl == null || !ctl.getSessionToken().equals(best.getSessionToken())) {
            detach();
            ctl = best;
            ctl.registerCallback(cb);
        }
        hasSession = true;
        readMeta(ctl.getMetadata());
        readState(ctl.getPlaybackState());
    }

    private void readMeta(MediaMetadata m) {
        if (m == null) {
            title = "";
            artist = "";
            art = null;
            duration = 0;
            return;
        }
        String t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null) t = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String a = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (a == null) a = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        title = t == null ? "" : t;
        artist = a == null ? "" : a;
        duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        art = b;
    }

    private void readState(PlaybackState s) {
        if (s == null) {
            playing = false;
            return;
        }
        playing = s.getState() == PlaybackState.STATE_PLAYING;
        posBase = s.getPosition();
        posTime = s.getLastPositionUpdateTime();
        speed = s.getPlaybackSpeed();
    }

    public long position() {
        long p = posBase;
        if (playing) p += (long) ((SystemClock.elapsedRealtime() - posTime) * speed);
        if (duration > 0 && p > duration) p = duration;
        return p < 0 ? 0 : p;
    }

    public void prev() {
        if (ctl != null) ctl.getTransportControls().skipToPrevious();
    }

    public void next() {
        if (ctl != null) ctl.getTransportControls().skipToNext();
    }

    public void seekTo(float frac) {
        if (ctl == null || duration <= 0) return;
        long t = (long) (duration * frac);
        ctl.getTransportControls().seekTo(t);
        posBase = t;
        posTime = SystemClock.elapsedRealtime();
    }

    public void toggle() {
        if (ctl == null) return;
        if (playing) ctl.getTransportControls().pause();
        else ctl.getTransportControls().play();
    }
}
