package com.goldshuffle;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import com.spotify.protocol.types.PlayerState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Set;

/**
 * Keeps gold shuffle running in the background.
 *
 * Queue manners:
 *  - Only LEAD song(s) of the gold order wait in Spotify's queue at any time; the next one is
 *    added each time a song starts. Songs other people queue are left alone.
 *  - Gold songs left in the queue from an earlier gold shuffle are skipped when they come up
 *    (Spotify has no "remove from queue", so this is the closest thing to clearing them).
 *  - If someone queues a song that's still waiting further down the gold order, it's taken
 *    out of the order so it doesn't play twice.
 *  - Starting a different playlist ends the gold shuffle; its queued songs then get skipped.
 *
 * Talks to the Spotify app directly when connected (works offline), otherwise the Web API.
 */
public class GoldService extends Service {
    static final String ACTION_STOP = "com.goldshuffle.STOP";
    static final String ACTION_STOP_ALL = "com.goldshuffle.STOP_ALL";
    static final String CHANNEL = "gold";
    static final int NOTIF_ID = 7;
    public static final int LEAD = 1;        // gold songs waiting in Spotify's queue
    public static final int CHUNK = 50;      // Web API fallback: songs in the first play request

    private HandlerThread thread;
    private Handler loop;
    private Spotify api;
    private SharedPreferences prefs;
    private GoldSession s;
    private String lastUri = null, lastCtx = null;
    private int idleTicks = 0;
    private int strangers = 0; // songs in a row from another playlist/album

    public static void start(Context c) { c.startForegroundService(new Intent(c, GoldService.class)); }

    public static boolean stayReady(Context c) {
        return c.getSharedPreferences("gold", MODE_PRIVATE).getBoolean("stay_ready", true);
    }

    /** Stop the current gold shuffle; its queued songs will be skipped when they come up. */
    public static void stopSession(Context c) {
        c.startForegroundService(new Intent(c, GoldService.class).setAction(ACTION_STOP));
    }

    /** Before a new gold shuffle: mark the old one's queued-but-unplayed songs as leftovers. */
    public static void retireOld(Context c) {
        GoldSession old = GoldSession.load(c);
        if (old != null) GoldSession.addStale(c, old.unplayedSent());
        GoldSession.clear(c);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("gold", MODE_PRIVATE);
        api = new Spotify(prefs);
        getSystemService(NotificationManager.class)
                .createNotificationChannel(new NotificationChannel(CHANNEL, "Gold shuffle", NotificationManager.IMPORTANCE_LOW));
        thread = new HandlerThread("gold-feeder");
        thread.start();
        loop = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, n);

        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP_ALL.equals(action)) {
            prefs.edit().putBoolean("stay_ready", false).apply();
            retireOld(this);
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) retireOld(this);

        s = GoldSession.load(this);
        if (s == null && !stayReady(this)) { shutdown(); return START_NOT_STICKY; }
        idleTicks = 0;
        lastUri = null;
        updateNotification();
        loop.removeCallbacksAndMessages(null);
        loop.post(this::tick);
        return START_STICKY;
    }

    // ---------- main loop: keeps the connection alive and polls as a backup to live updates ----------
    private void tick() {
        long next = 30_000;
        try {
            boolean kit = Toolkit.connected() || Toolkit.ensure(this, api.clientId(), 15);
            if (kit) Toolkit.listen(ps -> loop.post(() -> onState(ps)), ctx -> loop.post(() -> onContext(ctx == null ? null : ctx.uri)));
            if (s == null) {
                if (!stayReady(this)) { shutdown(); return; }
                updateNotification();
                next = 60_000;
            } else if (kit) {
                onState(Toolkit.state());
                next = 30_000;
            } else {
                next = pollWeb();
            }
        } catch (Exception e) {
            next = 15_000; // lost signal or Spotify hiccup: try again, never crash
        }
        loop.postDelayed(this::tick, next);
    }

    /** The listener started a different playlist/album: end gold shuffle right away. */
    private void onContext(String ctx) {
        lastCtx = ctx;
        if (s == null || ctx == null || ctx.isEmpty() || ctx.startsWith("spotify:track:") || ctx.startsWith("spotify:internal")) return;
        if (s.context.isEmpty()) {
            // e.g. Liked Songs: learn the name Spotify uses for it
            if (System.currentTimeMillis() - s.id < 20_000) { s.context = ctx; s.save(this); }
            return;
        }
        if (!ctx.equals(s.context) && System.currentTimeMillis() - s.id > 5_000) endSession();
    }

    private void onState(PlayerState ps) {
        try {
            if (ps == null || ps.track == null) { nothingPlaying(true); return; }
            idleTicks = 0;
            if (ps.track.uri.equals(lastUri)) return; // same song, nothing to do
            lastUri = ps.track.uri;
            onTrack(ps.track.uri, lastCtx, true);
        } catch (Exception ignored) {}
    }

    private long pollWeb() throws Exception {
        JSONObject p = api.get("/me/player");
        if (p == null || p.optJSONObject("item") == null) { nothingPlaying(false); return 10_000; }
        idleTicks = 0;
        JSONObject item = p.getJSONObject("item");
        String uri = item.optString("uri");
        String ctx = p.optJSONObject("context") != null ? p.getJSONObject("context").optString("uri") : null;
        if (!uri.equals(lastUri)) { lastUri = uri; onTrack(uri, ctx, false); }
        long left = item.optLong("duration_ms", 180_000) - p.optLong("progress_ms", 0);
        return Math.max(3_000, Math.min(30_000, left + 1_500));
    }

    /** Called once per song change. */
    private void onTrack(String uri, String ctx, boolean kit) throws Exception {
        // 1. A gold song left over from an earlier gold shuffle: skip it
        Set<String> stale = GoldSession.stale(this);
        boolean ours = s != null && s.find(uri, s.sentUpTo) >= 0;
        if (stale.remove(uri)) {
            GoldSession.setStale(this, stale);
            if (!ours) { skip(kit); return; }
        }
        if (s == null) return;

        // 2. One of ours: move along and keep LEAD songs waiting in the queue
        int i = s.find(uri, s.sentUpTo);
        if (i >= 0) {
            strangers = 0;
            s.cursor = i;
            topUp(kit);
            s.save(this);
            updateNotification();
            if (s.finished() && i == s.order.size() - 1) endWhenDone();
            return;
        }

        // 3. Someone queued a song that's still waiting in the gold order: don't play it twice
        int later = s.findLater(uri);
        if (later >= 0) {
            s.order.remove(later);
            s.save(this);
            updateNotification();
            return;
        }

        // 4. Something else is playing. A different playlist/album means the listener moved on.
        // Two such songs in a row (not one queued song) before we end it.
        if (ctx != null && !ctx.isEmpty() && !s.context.isEmpty()
                && !ctx.equals(s.context) && !ctx.startsWith("spotify:track:") && !ctx.startsWith("spotify:internal")) {
            if (++strangers >= 2) endSession();
        } else {
            strangers = 0;
        }
        // Otherwise it's a song someone queued: let it play; gold continues after it.
    }

    private void topUp(boolean kit) throws Exception {
        int want = Math.min(s.order.size(), s.cursor + 1 + LEAD);
        String dev = s.deviceId.isEmpty() ? "" : "&device_id=" + Spotify.enc(s.deviceId);
        while (s.sentUpTo < want) {
            String u = s.order.get(s.sentUpTo);
            if (kit) Toolkit.queue(u);
            else api.call("POST", "/me/player/queue?uri=" + Spotify.enc(u) + dev, null);
            s.sentUpTo++;
        }
    }

    private void skip(boolean kit) throws Exception {
        if (kit) Toolkit.skipNext();
        else api.call("POST", "/me/player/next" + (s != null && !s.deviceId.isEmpty() ? "?device_id=" + Spotify.enc(s.deviceId) : ""), null);
    }

    private void nothingPlaying(boolean kit) {
        if (s == null) return;
        if (++idleTicks > 30) endSession(); // ~15 min of silence
    }

    private void endWhenDone() {
        loop.postDelayed(() -> {
            if (s != null && s.cursor >= s.order.size() - 1) endSession();
        }, 10 * 60_000L);
    }

    private void endSession() {
        // Only retire the session we were running (a newer one may have just started)
        GoldSession onDisk = GoldSession.load(this);
        if (s != null && onDisk != null && onDisk.id == s.id) retireOld(this);
        else if (s != null && onDisk == null) GoldSession.addStale(this, s.unplayedSent());
        s = null;
        if (!stayReady(this)) { shutdown(); return; }
        updateNotification();
    }

    private void shutdown() {
        if (loop != null) loop.removeCallbacksAndMessages(null);
        s = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ---------- notification ----------
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_shuffle)
                .setContentIntent(open)
                .setOngoing(true);
        if (s != null) {
            b.setContentTitle("Gold shuffle on")
             .setContentText("Song " + (s.cursor + 1) + " of " + s.order.size() + (s.name.isEmpty() ? "" : " · " + s.name));
            PendingIntent stop = PendingIntent.getService(this, 1,
                    new Intent(this, GoldService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, "Stop gold shuffle", stop).build());
        } else {
            b.setContentTitle("Gold Shuffle ready")
             .setContentText(Toolkit.connected() ? "Connected to Spotify · works offline" : "Waiting for Spotify connection");
            PendingIntent off = PendingIntent.getService(this, 3,
                    new Intent(this, GoldService.class).setAction(ACTION_STOP_ALL), PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, "Turn off", off).build());
        }
        return b.build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, notification());
    }

    @Override
    public void onDestroy() {
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
