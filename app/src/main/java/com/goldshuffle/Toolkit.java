package com.goldshuffle;

import android.content.Context;

import com.spotify.android.appremote.api.ConnectionParams;
import com.spotify.android.appremote.api.Connector;
import com.spotify.android.appremote.api.SpotifyAppRemote;
import com.spotify.protocol.client.Result;
import com.spotify.protocol.types.ListItem;
import com.spotify.protocol.types.ListItems;
import com.spotify.protocol.types.PlayerContext;
import com.spotify.protocol.types.PlayerState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Spotify's on-phone toolkit (App Remote). Talks to the Spotify app directly, so once
 * connected it keeps working offline. A new connection needs internet, so the
 * connection is held open by GoldService for as long as possible.
 */
public final class Toolkit {
    public static final String SDK_REDIRECT = "goldshuffle://sdkauth";
    private static volatile SpotifyAppRemote remote;
    private static volatile boolean connecting = false;

    private Toolkit() {}

    public static boolean connected() { return remote != null && remote.isConnected(); }

    /** Connect if not already connected. Blocks up to timeoutSec. Call off the main thread. */
    public static boolean ensure(Context c, String clientId, int timeoutSec) {
        if (connected()) return true;
        if (clientId == null || clientId.isEmpty()) return false;
        if (connecting) return waitForConnection(timeoutSec);
        connecting = true;
        CountDownLatch done = new CountDownLatch(1);
        ConnectionParams p = new ConnectionParams.Builder(clientId)
                .setRedirectUri(SDK_REDIRECT)
                .showAuthView(false)
                .build();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                SpotifyAppRemote.connect(c.getApplicationContext(), p, new Connector.ConnectionListener() {
                    @Override public void onConnected(SpotifyAppRemote r) { remote = r; connecting = false; done.countDown(); }
                    @Override public void onFailure(Throwable t) { remote = null; connecting = false; lastError = t; done.countDown(); }
                }));
        try { done.await(timeoutSec, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        if (!connected()) connecting = false;
        return connected();
    }

    public static volatile Throwable lastError;

    private static boolean waitForConnection(int timeoutSec) {
        long end = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < end && connecting) {
            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
        }
        return connected();
    }

    public static void disconnect() {
        SpotifyAppRemote r = remote;
        remote = null;
        if (r != null) SpotifyAppRemote.disconnect(r);
    }

    // ---------- reading ----------
    /** Songs of any playlist/album the Spotify app can open, including ones you only follow. */
    public static List<String> readContext(String uri, String title, Spotify.Progress progress) throws Exception {
        if (!connected()) throw new Exception("Not connected to the Spotify app");
        ListItem parent = new ListItem(uri, uri, null, title == null ? "" : title, "", true, true);
        List<String> out = new ArrayList<>();
        int offset = 0;
        for (int guard = 0; guard < 2000; guard++) {
            Result<ListItems> r = remote.getContentApi().getChildrenOfItem(parent, 50, offset).await(20, TimeUnit.SECONDS);
            if (!r.isSuccessful()) {
                if (out.isEmpty()) throw new Exception("Spotify app couldn't list the songs: " + r.getErrorMessage());
                break; // keep what we have
            }
            ListItems page = r.getData();
            if (page == null || page.items == null || page.items.length == 0) break;
            for (ListItem it : page.items)
                if (it.uri != null && (it.uri.startsWith("spotify:track:") || it.uri.startsWith("spotify:episode:"))) out.add(it.uri);
            offset += page.items.length;
            if (progress != null) progress.loaded(out.size());
            if (page.total > 0 && offset >= page.total) break;
        }
        return out;
    }

    // ---------- player ----------
    public static PlayerState state() {
        if (!connected()) return null;
        Result<PlayerState> r = remote.getPlayerApi().getPlayerState().await(10, TimeUnit.SECONDS);
        return r.isSuccessful() ? r.getData() : null;
    }

    /** Current context (playlist/album) the Spotify app is playing from, or null. */
    public static PlayerContext context(int timeoutSec) {
        if (!connected()) return null;
        final PlayerContext[] got = new PlayerContext[1];
        CountDownLatch done = new CountDownLatch(1);
        com.spotify.protocol.client.Subscription<PlayerContext> sub = remote.getPlayerApi().subscribeToPlayerContext();
        sub.setEventCallback(ctx -> { got[0] = ctx; done.countDown(); });
        try { done.await(timeoutSec, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        sub.cancel();
        return got[0];
    }

    public static void play(String uri) throws Exception { check(remote.getPlayerApi().play(uri).await(10, TimeUnit.SECONDS), "play"); }
    public static void queue(String uri) throws Exception { check(remote.getPlayerApi().queue(uri).await(10, TimeUnit.SECONDS), "queue"); }
    public static void skipNext() throws Exception { check(remote.getPlayerApi().skipNext().await(10, TimeUnit.SECONDS), "skip"); }

    /** Start a playlist/album at a given song, so Spotify keeps the playlist as what's playing. */
    public static void playAt(String contextUri, int index) throws Exception {
        check(remote.getPlayerApi().skipToIndex(contextUri, index).await(10, TimeUnit.SECONDS), "play");
    }

    // ---------- live updates ----------
    public interface StateListener { void onState(PlayerState ps); }
    public interface ContextListener { void onContext(PlayerContext ctx); }
    private static SpotifyAppRemote subscribedTo;

    /** Subscribe to song/context changes on the current connection (once per connection). */
    public static synchronized void listen(StateListener st, ContextListener cx) {
        SpotifyAppRemote r = remote;
        if (r == null || !r.isConnected() || r == subscribedTo) return;
        subscribedTo = r;
        r.getPlayerApi().subscribeToPlayerState().setEventCallback(st::onState);
        r.getPlayerApi().subscribeToPlayerContext().setEventCallback(cx::onContext);
    }

    public static void shuffleOff() {
        try { remote.getPlayerApi().setShuffle(false).await(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
    }

    private static void check(Result<?> r, String what) throws Exception {
        if (!r.isSuccessful()) throw new Exception("Spotify app refused " + what + ": " + r.getErrorMessage());
    }
}
