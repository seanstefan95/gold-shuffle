package com.goldshuffle;

import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Minimal Spotify Web API client: PKCE sign-in, token refresh, JSON requests. */
public class Spotify {
    public static final String REDIRECT = "goldshuffle://callback";
    static final String SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing "
            + "playlist-read-private playlist-read-collaborative user-library-read";
    static final String API = "https://api.spotify.com/v1";

    public static class ApiException extends Exception {
        public final int code;
        ApiException(int code, String msg) { super(msg); this.code = code; }
    }

    private final SharedPreferences prefs;
    private static final SecureRandom RNG = new SecureRandom();

    public Spotify(SharedPreferences prefs) { this.prefs = prefs; }

    // ---------- settings / tokens ----------
    public String clientId() { return prefs.getString("client_id", ""); }
    public void setClientId(String id) { prefs.edit().putString("client_id", id.trim()).apply(); }
    public boolean isSignedIn() { return !prefs.getString("refresh_token", "").isEmpty(); }
    public void signOut() { prefs.edit().remove("access_token").remove("refresh_token").remove("expires_at").apply(); }

    // ---------- PKCE sign-in ----------
    public Uri buildAuthUri() throws Exception {
        byte[] raw = new byte[48];
        RNG.nextBytes(raw);
        String verifier = b64(raw);
        prefs.edit().putString("pkce_verifier", verifier).apply();
        String challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
                .appendQueryParameter("client_id", clientId())
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", REDIRECT)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("scope", SCOPES)
                .build();
    }

    public void exchangeCode(String code) throws Exception {
        String verifier = prefs.getString("pkce_verifier", "");
        JSONObject t = tokenRequest("grant_type=authorization_code&code=" + enc(code)
                + "&redirect_uri=" + enc(REDIRECT) + "&client_id=" + enc(clientId())
                + "&code_verifier=" + enc(verifier));
        saveTokens(t);
    }

    private void refresh() throws Exception {
        String rt = prefs.getString("refresh_token", "");
        if (rt.isEmpty()) throw new ApiException(401, "Not signed in");
        JSONObject t = tokenRequest("grant_type=refresh_token&refresh_token=" + enc(rt) + "&client_id=" + enc(clientId()));
        saveTokens(t);
    }

    private void saveTokens(JSONObject t) {
        SharedPreferences.Editor e = prefs.edit();
        e.putString("access_token", t.optString("access_token"));
        if (t.has("refresh_token")) e.putString("refresh_token", t.optString("refresh_token"));
        e.putLong("expires_at", System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000L - 60_000L);
        e.apply();
    }

    private JSONObject tokenRequest(String form) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://accounts.spotify.com/api/token").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream os = c.getOutputStream()) { os.write(form.getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code >= 400) throw new ApiException(code, "Sign-in failed (" + code + "): " + body);
        return new JSONObject(body);
    }

    // ---------- Web API ----------
    private String accessToken() throws Exception {
        if (System.currentTimeMillis() > prefs.getLong("expires_at", 0)) refresh();
        return prefs.getString("access_token", "");
    }

    /** Returns parsed JSON, or null for empty responses (204). */
    public JSONObject call(String method, String path, JSONObject body) throws Exception {
        boolean refreshed = false;
        for (int tries = 0; tries < 8; tries++) {
            HttpURLConnection c = (HttpURLConnection) new URL(path.startsWith("http") ? path : API + path).openConnection();
            c.setRequestMethod(method);
            c.setRequestProperty("Authorization", "Bearer " + accessToken());
            if (body != null || method.equals("PUT") || method.equals("POST")) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] b = (body == null ? "" : body.toString()).getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) { os.write(b); }
            }
            int code = c.getResponseCode();
            if (code == 401 && !refreshed) { refreshed = true; refresh(); continue; }
            if ((code == 429 || code >= 500) && tries < 7) {
                // Rate limited or a Spotify hiccup: wait and retry instead of failing
                long wait = 2;
                try { wait = Long.parseLong(c.getHeaderField("Retry-After")); } catch (Exception ignored) {}
                Thread.sleep(Math.min(30, Math.max(1, wait)) * 1000L);
                continue;
            }
            if (code == 204) return null;
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                String msg = text;
                try { msg = new JSONObject(text).getJSONObject("error").optString("message", text); } catch (Exception ignored) {}
                throw new ApiException(code, msg);
            }
            // Playback commands sometimes answer with a bare text code instead of JSON; that's still a success
            String t = text.trim();
            if (!t.startsWith("{")) return null;
            try { return new JSONObject(t); } catch (Exception notJson) { return null; }
        }
        throw new ApiException(0, "Spotify isn't responding, try again in a minute");
    }

    public JSONObject get(String path) throws Exception { return call("GET", path, null); }

    // ---------- tracks for a context ----------
    /** All playable track URIs for a playlist, album or Liked Songs context. */
    public interface Progress { void loaded(int count); }
    public Progress progress;

    public List<String> contextTracks(String contextUri) throws Exception {
        String[] p = contextUri.split(":");
        if (contextUri.contains(":collection")) return pagedUris("/me/tracks?limit=50", "track");
        if (p.length >= 3 && p[1].equals("playlist")) {
            String id = p[2];
            try {
                return pagedUris("/playlists/" + id + "/items?limit=50", null);
            } catch (ApiException e) {
                if (e.code != 404 && e.code != 403) throw e;
                return pagedUris("/playlists/" + id + "/tracks?limit=50", null);
            }
        }
        if (p.length >= 3 && p[1].equals("album")) return pagedUris("/albums/" + p[2] + "/tracks?limit=50", "");
        throw new ApiException(0, "Gold shuffle works on playlists, albums and Liked Songs");
    }

    /** wrapKey: "track"/"item" field holding the track, "" = item is the track, null = auto-detect. */
    private List<String> pagedUris(String path, String wrapKey) throws Exception {
        List<String> out = new ArrayList<>();
        String next = path;
        while (next != null && !next.isEmpty() && !next.equals("null")) {
            JSONObject page = get(next);
            if (page == null) break;
            JSONArray items = page.optJSONArray("items");
            if (items != null) for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                JSONObject t;
                if (wrapKey == null) t = it.has("track") ? it.optJSONObject("track") : it.optJSONObject("item");
                else if (wrapKey.isEmpty()) t = it;
                else t = it.optJSONObject(wrapKey);
                if (t == null) continue;
                String uri = t.optString("uri", "");
                if (t.has("is_playable") && !t.optBoolean("is_playable", true)) continue;
                if (uri.startsWith("spotify:track:") || uri.startsWith("spotify:episode:")) out.add(uri);
            }
            next = page.isNull("next") ? null : page.optString("next", null);
            if (progress != null) progress.loaded(out.size());
        }
        return out;
    }

    // ---------- helpers ----------
    static String b64(byte[] b) { return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP); }
    static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }
    static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    /** Fisher-Yates with a cryptographic RNG: every ordering equally likely. */
    public static <T> void goldShuffle(List<T> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = RNG.nextInt(i + 1);
            T tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }
}
