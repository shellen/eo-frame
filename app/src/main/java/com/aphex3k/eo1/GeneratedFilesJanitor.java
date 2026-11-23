package com.aphex3k.eo1;

import android.util.Log;

import com.aphex3k.media.local.CompatibilitySidecar;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Keeps the frame's storage from filling up with things the phone page generates. Safe to run at
 * any time and as often as wanted:
 * <ul>
 *   <li>Of each generated kind (newspaper front page, album grid, now-playing poster) only the newest
 *       file is kept. The phone page already replaces them; this catches interrupted uploads.</li>
 *   <li>Compatibility notes and gallery thumbnails whose file is gone are removed.</li>
 *   <li>Upload temp folders older than an hour are removed.</li>
 *   <li>The album-art cache is kept to its newest few dozen images.</li>
 * </ul>
 * Photos and kept art stills are never touched: those are the user's choices.
 */
public final class GeneratedFilesJanitor {

    private static final String TAG = "EO1-janitor";
    static final String[] SINGLE_KINDS = {"nyt-frontpage-", "spotify-grid-", "spotify-nowplaying-"};
    private static final long STALE_TEMP_MS = 3600000L;

    private GeneratedFilesJanitor() {
    }

    /** @return how many files were removed */
    public static synchronized int run(File filesDir) {
        int removed = 0;
        File up = new File(filesDir, UploadedMedia.DIR_NAME);
        File[] files = up.listFiles();
        if (files != null) {
            for (String prefix : SINGLE_KINDS) {
                File newest = null;
                for (File f : files) {
                    if (isMedia(f) && f.getName().startsWith(prefix)
                            && (newest == null || f.lastModified() > newest.lastModified())) {
                        newest = f;
                    }
                }
                for (File f : files) {
                    if (isMedia(f) && f.getName().startsWith(prefix) && f != newest) {
                        removed += delete(f);
                        removed += delete(CompatibilitySidecar.sidecarFor(f));
                    }
                }
            }
            long now = System.currentTimeMillis();
            files = up.listFiles();
            Set<String> names = new HashSet<>();
            for (File f : files == null ? new File[0] : files) {
                if (f.isFile()) {
                    names.add(f.getName());
                }
            }
            for (File f : files == null ? new File[0] : files) {
                String n = f.getName();
                if (f.isFile() && n.endsWith(CompatibilitySidecar.SUFFIX)
                        && !names.contains(n.substring(0, n.length() - CompatibilitySidecar.SUFFIX.length()))) {
                    removed += delete(f);
                } else if (f.isDirectory() && n.startsWith("incoming_") && now - f.lastModified() > STALE_TEMP_MS) {
                    removed += deleteTree(f);
                }
            }
            // thumbnails of files that no longer exist
            File[] thumbs = new File(filesDir, "thumbs").listFiles();
            if (thumbs != null) {
                Set<String> keys = new HashSet<>();
                for (String n : names) {
                    keys.add(WebServer.thumbKey(n) + "_");
                }
                for (File t : thumbs) {
                    String tn = t.getName();
                    int us = tn.indexOf('_', tn.indexOf('_') + 1);
                    String key = us > 0 ? tn.substring(0, us + 1) : tn;
                    if (!keys.contains(key)) {
                        removed += delete(t);
                    }
                }
            }
        }
        File cache = new File(filesDir, "music-cache");
        if (cache.isDirectory()) {
            int before = count(cache);
            MusicService.trimCache(cache);
            removed += Math.max(0, before - count(cache));
        }
        if (removed > 0) {
            Log.i(TAG, "removed " + removed + " generated/orphaned file(s)");
        }
        return removed;
    }

    private static boolean isMedia(File f) {
        return f.isFile() && !f.getName().endsWith(CompatibilitySidecar.SUFFIX);
    }

    private static int count(File dir) {
        String[] l = dir.list();
        return l == null ? 0 : l.length;
    }

    private static int delete(File f) {
        return f.exists() && f.delete() ? 1 : 0;
    }

    private static int deleteTree(File d) {
        int n = 0;
        File[] kids = d.listFiles();
        if (kids != null) {
            for (File k : kids) {
                n += k.isDirectory() ? deleteTree(k) : delete(k);
            }
        }
        return n + delete(d);
    }
}
