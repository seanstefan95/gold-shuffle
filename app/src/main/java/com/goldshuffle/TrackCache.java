package com.goldshuffle;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Saved song lists (one per playlist) and the playlist menu, so gold shuffle works offline. */
public final class TrackCache {
    private TrackCache() {}

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "tracks");
        d.mkdirs();
        return d;
    }

    private static File file(Context c, String key) {
        return new File(dir(c), key.replaceAll("[^A-Za-z0-9]", "_") + ".txt");
    }

    public static void save(Context c, String key, List<String> lines) {
        try {
            File tmp = new File(dir(c), "tmp.txt");
            try (FileOutputStream o = new FileOutputStream(tmp)) {
                o.write(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            }
            tmp.renameTo(file(c, key));
        } catch (Exception ignored) {}
    }

    /** Empty list if nothing saved. */
    public static List<String> load(Context c, String key) {
        try (FileInputStream in = new FileInputStream(file(c, key))) {
            String s = Spotify.read(in).trim();
            return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split("\n")));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static boolean has(Context c, String key) { return file(c, key).exists(); }
}
