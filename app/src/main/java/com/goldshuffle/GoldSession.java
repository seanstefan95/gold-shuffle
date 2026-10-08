package com.goldshuffle;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The full gold-shuffled order for one listening session, saved to disk so it
 * survives the app being closed. Spotify is only ever handed a small window of it.
 *
 *   order     every song, already shuffled once as a whole (no blocks)
 *   cursor    index of the song playing now
 *   sentUpTo  songs [0, sentUpTo) have been handed to Spotify
 */
public class GoldSession {
    public List<String> order = new ArrayList<>();
    public int cursor = 0;
    public int sentUpTo = 0;
    public String name = "";
    public String deviceId = "";
    public String context = ""; // playlist/album the gold shuffle belongs to
    public long id = System.currentTimeMillis();

    private static File file(Context c) { return new File(c.getFilesDir(), "gold_session.json"); }

    public static GoldSession load(Context c) {
        try (FileInputStream in = new FileInputStream(file(c))) {
            JSONObject o = new JSONObject(Spotify.read(in));
            GoldSession s = new GoldSession();
            JSONArray a = o.getJSONArray("order");
            s.order = new ArrayList<>(a.length());
            for (int i = 0; i < a.length(); i++) s.order.add(a.getString(i));
            s.cursor = o.optInt("cursor");
            s.sentUpTo = o.optInt("sentUpTo");
            s.name = o.optString("name");
            s.deviceId = o.optString("deviceId");
            s.context = o.optString("context");
            s.id = o.optLong("id");
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    public void save(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("order", new JSONArray(order));
            o.put("cursor", cursor);
            o.put("sentUpTo", sentUpTo);
            o.put("name", name);
            o.put("deviceId", deviceId);
            o.put("context", context);
            o.put("id", id);
            File tmp = new File(c.getFilesDir(), "gold_session.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(o.toString().getBytes(StandardCharsets.UTF_8)); }
            tmp.renameTo(file(c));
        } catch (Exception ignored) {}
    }

    public static void clear(Context c) { file(c).delete(); }

    /** Where `uri` sits in the order, searching near the cursor first (playlists can repeat songs). */
    public int find(String uri, int windowEnd) {
        int from = Math.max(0, cursor - 3);
        int to = Math.min(order.size(), Math.max(windowEnd, cursor + 1));
        for (int i = from; i < to; i++) if (order.get(i).equals(uri)) return i;
        return -1;
    }

    public boolean finished() { return sentUpTo >= order.size(); }

    /** Index of uri among songs not yet handed to Spotify, or -1. */
    public int findLater(String uri) {
        for (int i = sentUpTo; i < order.size(); i++) if (order.get(i).equals(uri)) return i;
        return -1;
    }

    /** Songs handed to Spotify that haven't played yet. */
    public java.util.List<String> unplayedSent() {
        return new java.util.ArrayList<>(order.subList(Math.min(order.size(), cursor + 1), Math.min(order.size(), sentUpTo)));
    }

    // ---------- leftovers: songs Gold Shuffle queued that never played ----------
    public static java.util.Set<String> stale(Context c) {
        return new java.util.HashSet<>(c.getSharedPreferences("gold", Context.MODE_PRIVATE)
                .getStringSet("stale", new java.util.HashSet<>()));
    }
    public static void setStale(Context c, java.util.Set<String> set) {
        c.getSharedPreferences("gold", Context.MODE_PRIVATE).edit().putStringSet("stale", new java.util.HashSet<>(set)).apply();
    }
    public static void addStale(Context c, java.util.Collection<String> uris) {
        java.util.Set<String> s = stale(c);
        s.addAll(uris);
        setStale(c, s);
    }
}
