package com.goldshuffle;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final String ACTION_SHUFFLE_NOW = "com.goldshuffle.SHUFFLE_NOW";
    static final int GOLD = Color.parseColor("#E8B923");
    static final int BG = Color.parseColor("#121212");

    private Spotify api;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences prefs = getSharedPreferences("gold", MODE_PRIVATE);
        api = new Spotify(prefs);
        api.progress = n -> toastStatus("Loading songs… " + n);
        // Android 13+: allow the "Gold shuffle on" notification with its Stop button
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        render();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data != null && data.toString().startsWith(Spotify.REDIRECT)) {
            String code = data.getQueryParameter("code");
            String err = data.getQueryParameter("error");
            if (code == null) { toastStatus("Sign-in cancelled" + (err != null ? ": " + err : "")); return; }
            setStatus("Signing in…");
            bg.execute(() -> {
                try {
                    api.exchangeCode(code);
                    ui.post(this::render);
                } catch (Exception e) {
                    toastStatus(e.getMessage());
                }
            });
            intent.setData(null);
        } else if (ACTION_SHUFFLE_NOW.equals(intent.getAction()) && api.isSignedIn()) {
            intent.setAction(Intent.ACTION_MAIN);
            goldShuffleCurrent();
        }
    }

    // ================= screens =================
    private void render() {
        if (api.clientId().isEmpty()) showSetup();
        else if (!api.isSignedIn()) showSignIn();
        else showMain();
    }

    private LinearLayout page() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int p = dp(20);
        root.setPadding(p, p * 2, p, p);
        TextView title = new TextView(this);
        String ver = "";
        try { ver = "  build " + getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception ignored) {}
        title.setText("Gold Shuffle");
        TextView build = new TextView(this);
        build.setText(ver.trim());
        build.setTextColor(Color.GRAY);
        build.setTextSize(12);
        title.setTextColor(GOLD);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(build);
        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(15);
        status.setPadding(0, dp(8), 0, dp(16));
        root.addView(status);
        setContentView(root);
        return root;
    }

    /** This install's signing fingerprint, read from the app itself (what Spotify's dashboard asks for). */
    private String sha1() {
        try {
            android.content.pm.Signature[] sigs;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                sigs = getPackageManager().getPackageInfo(getPackageName(),
                        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners();
            } else {
                sigs = getPackageManager().getPackageInfo(getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES).signatures;
            }
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) sb.append(i == 0 ? "" : ":").append(String.format("%02X", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "(couldn't read)";
        }
    }

    private TextView text(String s, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    /** A value to type into Spotify's dashboard, with a Copy button. */
    private View copyRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Color.GRAY);
        l.setTextSize(12);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(Color.WHITE);
        v.setTextSize(14);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextIsSelectable(true);
        col.addView(l);
        col.addView(v);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button copy = new Button(this);
        copy.setText("Copy");
        copy.setAllCaps(false);
        copy.setTextColor(Color.BLACK);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        g.setColor(GOLD);
        copy.setBackground(g);
        copy.setOnClickListener(x -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value));
            setStatus("Copied " + label + ".");
        });
        row.addView(copy, new LinearLayout.LayoutParams(dp(76), dp(38)));
        return row;
    }

    private void showSetup() {
        LinearLayout root = page();
        ((ViewGroup) root.getParent()).removeView(root);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        setContentView(sv);

        setStatus("One-time setup, about 2 minutes. You need Spotify Premium and a computer or phone browser.");
        int light = Color.parseColor("#DDDDDD");
        root.addView(text("1. Create your Spotify developer app", 17, GOLD, true));
        root.addView(text("Open the dashboard, log in with your normal Spotify account, accept the terms, and tap Create app. Name and description can be anything.", 14, light, false));
        Button dash = button("Open Spotify developer dashboard", false);
        dash.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.spotify.com/dashboard"))));
        root.addView(dash);

        root.addView(text("2. Redirect URIs: add both", 17, GOLD, true));
        root.addView(copyRow("Redirect URI 1", Spotify.REDIRECT));
        root.addView(copyRow("Redirect URI 2", Toolkit.SDK_REDIRECT));

        root.addView(text("3. APIs: tick Web API and Android", 17, GOLD, true));
        root.addView(text("Under Android packages, fill in both boxes, then press the purple Add button (easy to miss), then Save at the bottom.", 14, light, false));
        root.addView(copyRow("Package name", getPackageName()));
        root.addView(copyRow("SHA1 fingerprint", sha1()));

        root.addView(text("4. Paste your Client ID", 17, GOLD, true));
        root.addView(text("In your app's Settings on the dashboard, copy the Client ID (not the Client Secret) and paste it here.", 14, light, false));
        EditText field = new EditText(this);
        field.setHint("Client ID");
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(Color.GRAY);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setSingleLine(true);
        root.addView(field);
        Button paste = button("Paste", false);
        paste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0)
                field.setText(cm.getPrimaryClip().getItemAt(0).coerceToText(this));
        });
        root.addView(paste);
        Button save = button("Save", true);
        save.setOnClickListener(v -> {
            String id = field.getText().toString().trim();
            if (!id.matches("[0-9a-fA-F]{32}")) { setStatus("That doesn't look like a Client ID (32 letters and numbers)."); sv.smoothScrollTo(0, 0); return; }
            api.setClientId(id);
            render();
        });
        root.addView(save);
    }

    private void showSignIn() {
        LinearLayout root = page();
        setStatus("Sign in with Spotify so Gold Shuffle can read your playlists and control playback.");
        Button signIn = button("Sign in with Spotify", true);
        signIn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, api.buildAuthUri()));
            } catch (Exception e) {
                setStatus("Couldn't open sign-in: " + e.getMessage());
            }
        });
        root.addView(signIn);
        Button change = button("Change Client ID", false);
        change.setOnClickListener(v -> { api.setClientId(""); render(); });
        root.addView(change);
    }

    private final List<String> playlistUris = new ArrayList<>();
    private final List<String> playlistNames = new ArrayList<>();

    private void showMain() {
        LinearLayout root = page();
        setStatus("Pick a playlist, or gold-shuffle what's playing now.");
        Button now = button("★  Gold shuffle what's playing", true);
        now.setOnClickListener(v -> goldShuffleCurrent());
        root.addView(now);

        TextView label = new TextView(this);
        label.setText("Your playlists");
        label.setTextColor(Color.WHITE);
        label.setTextSize(18);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(0, dp(20), 0, dp(6));
        root.addView(label);

        ListView list = new ListView(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, playlistNames);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> goldShufflePlaylist(playlistUris.get(pos), playlistNames.get(pos)));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button stop = button("Stop gold shuffle", false);
        stop.setOnClickListener(v -> { GoldService.stopSession(this); setStatus("Gold shuffle stopped."); });
        root.addView(stop);

        Button ready = button(readyLabel(), false);
        ready.setOnClickListener(v -> {
            boolean on = !GoldService.stayReady(this);
            prefs().edit().putBoolean("stay_ready", on).apply();
            if (on) GoldService.start(this);
            else startService(new Intent(this, GoldService.class).setAction(GoldService.ACTION_STOP_ALL));
            ready.setText(readyLabel());
            setStatus(on ? "Offline-ready on: Gold Shuffle stays connected to Spotify so it keeps working if you lose signal."
                         : "Offline-ready off.");
        });
        root.addView(ready);

        Button out = button("Sign out", false);
        out.setOnClickListener(v -> { api.signOut(); prefs().edit().remove("toolkit_ok").apply(); render(); });
        root.addView(out);

        // Playlist menu: saved copy first (works offline), then refresh online
        List<String> saved = TrackCache.load(this, "_menu");
        if (!saved.isEmpty()) setMenu(saved, adapter);
        bg.execute(() -> {
            try {
                List<String> menu = new ArrayList<>();
                menu.add("spotify:collection\t♥  Liked Songs");
                String next = "/me/playlists?limit=50";
                while (next != null) {
                    JSONObject pg = api.get(next);
                    if (pg == null) break;
                    JSONArray items = pg.optJSONArray("items");
                    if (items != null) for (int i = 0; i < items.length(); i++) {
                        JSONObject pl = items.optJSONObject(i);
                        if (pl == null) continue;
                        menu.add(pl.optString("uri") + "\t" + pl.optString("name").replace("\t", " "));
                    }
                    next = pg.isNull("next") ? null : pg.optString("next", null);
                }
                TrackCache.save(this, "_menu", menu);
                ui.post(() -> setMenu(menu, adapter));
            } catch (Exception e) {
                if (saved.isEmpty()) toastStatus("Couldn't load playlists: " + e.getMessage());
                else toastStatus("Offline: showing your saved playlists. ⤓ = saved for offline.");
            }
        });

        // One-time approval for the on-phone toolkit, then keep it connected
        if (!prefs().getBoolean("toolkit_ok", false)) approveToolkit();
        else if (GoldService.stayReady(this)) GoldService.start(this);
    }

    private void setMenu(List<String> menu, ArrayAdapter<String> adapter) {
        playlistUris.clear();
        playlistNames.clear();
        for (String line : menu) {
            int t = line.indexOf('\t');
            if (t < 0) continue;
            String uri = line.substring(0, t);
            String name = line.substring(t + 1).replace("  ⤓", "");
            playlistUris.add(uri);
            playlistNames.add(TrackCache.has(this, cacheKey(uri)) ? name + "  ⤓" : name);
        }
        menuAdapter = adapter;
        adapter.notifyDataSetChanged();
    }

    private ArrayAdapter<String> menuAdapter;

    /** Re-draw the ⤓ marks after a playlist gets saved. */
    private void refreshMarks() {
        ui.post(() -> {
            if (menuAdapter == null) return;
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < playlistUris.size(); i++) lines.add(playlistUris.get(i) + "\t" + playlistNames.get(i));
            setMenu(lines, menuAdapter);
        });
    }

    private String readyLabel() { return "Offline-ready: " + (GoldService.stayReady(this) ? "ON" : "OFF"); }
    private SharedPreferences prefs() { return getSharedPreferences("gold", MODE_PRIVATE); }

    // ================= toolkit approval =================
    static final int TOOLKIT_AUTH = 42;

    private void approveToolkit() {
        try {
            com.spotify.sdk.android.auth.AuthorizationRequest req =
                    new com.spotify.sdk.android.auth.AuthorizationRequest.Builder(api.clientId(),
                            com.spotify.sdk.android.auth.AuthorizationResponse.Type.TOKEN, Toolkit.SDK_REDIRECT)
                            .setScopes(new String[]{"app-remote-control"})
                            .build();
            com.spotify.sdk.android.auth.AuthorizationClient.openLoginActivity(this, TOOLKIT_AUTH, req);
        } catch (Exception e) {
            setStatus("Couldn't open Spotify approval: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != TOOLKIT_AUTH) return;
        com.spotify.sdk.android.auth.AuthorizationResponse r =
                com.spotify.sdk.android.auth.AuthorizationClient.getResponse(resultCode, data);
        if (r.getType() == com.spotify.sdk.android.auth.AuthorizationResponse.Type.TOKEN) {
            prefs().edit().putBoolean("toolkit_ok", true).apply();
            setStatus("Connected to the Spotify app. Gold shuffle now works with any playlist, and offline once connected.");
            if (GoldService.stayReady(this)) GoldService.start(this);
        } else {
            String err = r.getError() == null ? "" : r.getError();
            String hint = err.contains("AUTHENTICATION_SERVICE_UNAVAILABLE")
                    ? " This almost always means the Android package isn't saved on your Spotify dashboard: check step 3 (press Add, then Save), then sign out and in again here."
                    : "";
            setStatus("Spotify app approval didn't finish (" + r.getType() + (err.isEmpty() ? "" : ": " + err) + ")." + hint
                    + " Gold shuffle still works online for your own playlists.");
        }
    }

    // ================= songs =================
    /** Liked Songs has several URI spellings; store them under one key. */
    static String cacheKey(String uri) { return uri.contains(":collection") ? "liked" : uri; }

    private boolean kit() {
        return prefs().getBoolean("toolkit_ok", false) && (Toolkit.connected() || Toolkit.ensure(this, api.clientId(), 8));
    }

    /**
     * Songs for a playlist/album/Liked Songs: through the Spotify app (any playlist), else the
     * Web API, else the copy saved last time you were online. Fresh results are saved.
     */
    private List<String> tracksFor(String uri, String title) throws Exception {
        List<String> t = null;
        Exception err = null;
        boolean liked = uri.contains(":collection");
        if (!liked && kit()) {
            try { t = Toolkit.readContext(uri, title, api.progress); } catch (Exception e) { err = e; }
        }
        if (t == null || t.isEmpty()) {
            try { t = api.contextTracks(uri); } catch (Exception e) { if (err == null) err = e; }
        }
        if ((t == null || t.isEmpty()) && liked && kit()) {
            try { t = Toolkit.readContext(uri, title, api.progress); } catch (Exception e) { if (err == null) err = e; }
        }
        if (t != null && !t.isEmpty()) {
            TrackCache.save(this, cacheKey(uri), t);
            refreshMarks();
            return t;
        }
        List<String> saved = TrackCache.load(this, cacheKey(uri));
        if (!saved.isEmpty()) return saved;
        throw err != null ? err : new Exception("No playable songs found.");
    }

    // ================= gold shuffle =================
    /**
     * Leftover gold songs from the last run may still sit in Spotify's queue (they get skipped
     * when they come up). Keep them out of the first few places of the new order so the new
     * run doesn't line up right behind its own skipped copy.
     */
    private void keepLeftoversBack(List<String> order, int from) {
        java.util.Set<String> stale = GoldSession.stale(this);
        if (stale.isEmpty()) return;
        java.security.SecureRandom rng = new java.security.SecureRandom();
        int front = Math.min(order.size(), from + GoldService.LEAD + 2);
        int backStart = front + stale.size();
        for (int k = from; k < front; k++) {
            if (!stale.contains(order.get(k)) || backStart >= order.size()) continue;
            int j = backStart + rng.nextInt(order.size() - backStart);
            String tmp = order.get(k); order.set(k, order.get(j)); order.set(j, tmp);
        }
    }

    private void queueLead(List<String> order, int from, String deviceId, boolean kit) throws Exception {
        String dev = deviceId != null && !deviceId.isEmpty() ? "&device_id=" + Spotify.enc(deviceId) : "";
        int end = Math.min(order.size(), from + GoldService.LEAD);
        for (int k = from; k < end; k++) {
            if (kit) Toolkit.queue(order.get(k));
            else api.call("POST", "/me/player/queue?uri=" + Spotify.enc(order.get(k)) + dev, null);
        }
    }

    /** Shuffle what's playing now; the current song keeps playing and is left out of the pool. */
    private void goldShuffleCurrent() {
        if (busy) return;
        busy = true;
        setStatus("Gold shuffling…");
        bg.execute(() -> {
            try {
                String current, ctxUri, title, deviceId = "", songName;
                boolean kit = kit();
                if (kit) {
                    com.spotify.protocol.types.PlayerState ps = Toolkit.state();
                    if (ps == null || ps.track == null) throw new Exception("Nothing is playing. Start a playlist in Spotify, or pick one below.");
                    com.spotify.protocol.types.PlayerContext ctx = Toolkit.context(5);
                    if (ctx == null || ctx.uri == null || ctx.uri.isEmpty())
                        throw new Exception("What's playing isn't from a playlist or album. Pick a playlist below.");
                    current = ps.track.uri; songName = ps.track.name; ctxUri = ctx.uri; title = ctx.title;
                } else {
                    JSONObject p = api.get("/me/player");
                    if (p == null || p.optJSONObject("item") == null)
                        throw new Exception("Nothing is playing. Start a playlist in Spotify, or pick one below.");
                    JSONObject ctx = p.optJSONObject("context");
                    if (ctx == null) throw new Exception("What's playing isn't from a playlist or album. Pick a playlist below.");
                    current = p.getJSONObject("item").optString("uri");
                    songName = p.getJSONObject("item").optString("name");
                    ctxUri = ctx.optString("uri"); title = "";
                    if (p.optJSONObject("device") != null) deviceId = p.getJSONObject("device").optString("id");
                }
                List<String> tracks = new ArrayList<>(tracksFor(ctxUri, title));
                tracks.remove(current); // pull the current song out of the pool
                Spotify.goldShuffle(tracks);
                List<String> order = new ArrayList<>();
                order.add(current);
                order.addAll(tracks);
                GoldService.retireOld(this);
                keepLeftoversBack(order, 1);
                queueLead(order, 1, deviceId, kit); // behind the current song, no interruption
                saveSession(order, Math.min(order.size(), 1 + GoldService.LEAD), title, deviceId, ctxUri);
                toastStatus("Gold shuffle on: " + tracks.size() + " songs shuffled after “" + songName + "”.");
            } catch (Exception e) {
                toastStatus(e.getMessage());
            } finally {
                busy = false;
            }
        });
    }

    /** Shuffle a whole playlist and start it. */
    private void goldShufflePlaylist(String uri, String name) {
        if (busy) return;
        busy = true;
        String clean = name.replace("  ⤓", "");
        setStatus("Gold shuffling " + clean + "…");
        bg.execute(() -> {
            try {
                List<String> original = tracksFor(uri, clean);
                if (original.isEmpty()) throw new Exception("No playable songs in " + clean + ".");
                List<String> order = new ArrayList<>(original);
                Spotify.goldShuffle(order);
                GoldService.retireOld(this);
                keepLeftoversBack(order, 0);
                boolean liked = uri.contains(":collection");
                String deviceId = "";
                if (kit()) {
                    Toolkit.shuffleOff();
                    boolean started = false;
                    if (!liked) {
                        // Start inside the playlist so Spotify shows it as what's playing
                        try { Toolkit.playAt(uri, original.indexOf(order.get(0))); started = true; } catch (Exception ignored) {}
                    }
                    if (!started) Toolkit.play(order.get(0));
                    queueLead(order, 1, "", true);
                } else {
                    deviceId = pickDevice();
                    String dev = "&device_id=" + Spotify.enc(deviceId);
                    api.call("PUT", "/me/player/shuffle?state=false" + dev, null);
                    JSONObject body = new JSONObject();
                    body.put("uris", new JSONArray(java.util.Collections.singletonList(order.get(0))));
                    api.call("PUT", "/me/player/play?device_id=" + Spotify.enc(deviceId), body);
                    queueLead(order, 1, deviceId, false);
                    openSpotify();
                }
                saveSession(order, Math.min(order.size(), 1 + GoldService.LEAD), clean, deviceId, liked ? "" : uri);
                toastStatus("Gold shuffle: playing " + clean + " (" + order.size() + " songs) in a true random order.");
            } catch (Exception e) {
                toastStatus(e.getMessage());
            } finally {
                busy = false;
            }
        });
    }

    private void saveSession(List<String> order, int sentUpTo, String name, String deviceId, String context) {
        GoldSession sess = new GoldSession();
        sess.order = order;
        sess.cursor = 0;
        sess.sentUpTo = sentUpTo;
        sess.name = name == null ? "" : name;
        sess.deviceId = deviceId == null ? "" : deviceId;
        sess.context = context == null ? "" : context;
        sess.save(this);
        ui.post(() -> GoldService.start(this));
    }

    private String pickDevice() throws Exception {
        JSONObject r = api.get("/me/player/devices");
        JSONArray ds = r == null ? null : r.optJSONArray("devices");
        if (ds == null || ds.length() == 0) {
            openSpotify();
            throw new Exception("Spotify isn't open on any device. I opened it — tap the playlist again in a few seconds.");
        }
        JSONObject phone = null;
        for (int i = 0; i < ds.length(); i++) {
            JSONObject d = ds.getJSONObject(i);
            if (d.optBoolean("is_active")) return d.optString("id");
            if (phone == null && "Smartphone".equalsIgnoreCase(d.optString("type"))) phone = d;
        }
        return (phone != null ? phone : ds.getJSONObject(0)).optString("id");
    }

    private void openSpotify() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.spotify.music");
        if (i != null) ui.post(() -> startActivity(i));
    }

    // ================= ui helpers =================
    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(primary ? Color.BLACK : Color.WHITE);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setCornerRadius(dp(28));
        bgd.setColor(primary ? GOLD : Color.parseColor("#2A2A2A"));
        b.setBackground(bgd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private void setStatus(String s) { if (status != null) status.setText(s); }
    private void toastStatus(String s) { ui.post(() -> setStatus(s == null ? "Something went wrong" : s)); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
