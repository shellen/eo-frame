package com.aphex3k.eo1;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Which kinds of things the frame puts in rotation: the user's photos, the generative art page,
 * the newspaper front page and Spotify images. Stored as {@code wall-sources.json} next to the
 * uploads folder and edited from the phone page. Uploaded files are sorted into those kinds by
 * name prefix, which is how the phone page names the files it makes.
 */
public final class WallSources {

    public static final String FILE_NAME = "wall-sources.json";
    public static final String ART_ID_PREFIX = "art-";
    public static final String MUSIC_ID = "music-1";
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    public boolean photos = true;
    public boolean art = false;
    public boolean nyt = true;
    public boolean spotify = true;
    /** How many art entries sit in each rotation cycle (more = art comes up more often). */
    public int artCount = 1;
    /** The live music page (needs Spotify connected on the frame). */
    public boolean music = false;
    /** While something is playing, keep the music page on screen. */
    public boolean musicTakeover = true;
    public String musicStyle = "kinetic";
    /** Hero line: artist, track or date. */
    public String musicLead = "artist";
    /** Show timed lyrics (from LRCLIB) on the music page when a song has them. Off by default. */
    public boolean musicLyrics = false;
    /** Comma-separated art style ids to choose from; empty = all styles. */
    public String artStyles = "";

    /** The kind of an uploaded file, from its name. */
    public static String kindOf(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.US);
        if (n.startsWith("nyt-frontpage-")) {
            return "nyt";
        }
        if (n.startsWith("spotify-")) {
            return "spotify";
        }
        if (n.startsWith("art-")) {
            return "art";
        }
        return "photos";
    }

    public boolean allows(String fileName) {
        String k = kindOf(fileName);
        if ("nyt".equals(k)) {
            return nyt;
        }
        if ("spotify".equals(k)) {
            return spotify;
        }
        if ("art".equals(k)) {
            // A kept art file is only the record (and thumbnail) of a kept piece. The frame replays
            // the piece live through a web entry instead, so the still itself is never shown.
            return false;
        }
        return photos;
    }

    /** A kept piece: which style and seed to replay, parsed from its record file's name. */
    public static final class KeptArt {
        public final String style;
        public final long seed;
        public final String fileName;

        KeptArt(String style, long seed, String fileName) {
            this.style = style;
            this.seed = seed;
            this.fileName = fileName;
        }

        public String backendId() {
            return ART_ID_PREFIX + "keep-" + style + "-" + Long.toString(seed, 36);
        }

        public String url(int port) {
            return "http://127.0.0.1:" + port + "/art/?style=" + style + "&seed=" + seed;
        }
    }

    private static final java.util.regex.Pattern KEPT_NAME =
            java.util.regex.Pattern.compile("^art-([a-z0-9]+(?:-[a-z0-9]+)*)-([a-z0-9]{1,8})\\.jpe?g$");
    /** Older kept files were named after the style's display name rather than its id. */
    private static final String[][] LEGACY_STYLE_NAMES = {
        {"wandering-squares", "squares"}, {"pointillist-swirl", "pointillist"}, {"drifting-dots", "pointillist2"},
        {"metric-wave", "metricwave"}, {"breathing-fabric", "fabric"}, {"light-cone", "cone"},
        {"watercolor-wash", "watercolor"}, {"bauhaus-blocks", "blocks"}, {"striped-sunset", "sunset"},
        {"contour-rings", "contours"}, {"diagonal-stripes", "stripes"}
    };

    /** Parses {@code art-<style>-<seed in base 36>.jpg}; null when the name is not a kept-art record. */
    public static KeptArt keptArtFor(String fileName) {
        if (fileName == null) {
            return null;
        }
        java.util.regex.Matcher m = KEPT_NAME.matcher(fileName.toLowerCase(java.util.Locale.US));
        if (!m.matches()) {
            return null;
        }
        String style = m.group(1);
        for (String[] legacy : LEGACY_STYLE_NAMES) {
            if (legacy[0].equals(style)) {
                style = legacy[1];
            }
        }
        if (!style.matches("[a-z0-9]{1,20}")) {
            return null;
        }
        try {
            return new KeptArt(style, Long.parseLong(m.group(2), 36), fileName);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Reads {@code dir/wall-sources.json}; a missing or unreadable file means the defaults. */
    public static WallSources read(File dir) {
        WallSources s = new WallSources();
        if (dir == null) {
            return s;
        }
        File f = new File(dir, FILE_NAME);
        if (!f.isFile() || f.length() > 4096) {
            return s;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            s.apply(JsonParser.parseString(new String(b, 0, off, UTF_8)).getAsJsonObject());
        } catch (Exception ignored) {
            // defaults
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
        return s;
    }

    /** Copies any recognised fields from {@code o}. */
    public void apply(JsonObject o) {
        if (o.has("photos")) {
            photos = o.get("photos").getAsBoolean();
        }
        if (o.has("art")) {
            art = o.get("art").getAsBoolean();
        }
        if (o.has("nyt")) {
            nyt = o.get("nyt").getAsBoolean();
        }
        if (o.has("spotify")) {
            spotify = o.get("spotify").getAsBoolean();
        }
        if (o.has("music")) {
            music = o.get("music").getAsBoolean();
        }
        if (o.has("musicTakeover")) {
            musicTakeover = o.get("musicTakeover").getAsBoolean();
        }
        if (o.has("musicLyrics")) {
            musicLyrics = o.get("musicLyrics").getAsBoolean();
        }
        if (o.has("musicStyle")) {
            String v = o.get("musicStyle").getAsString();
            musicStyle = v.matches("[a-z]{1,20}") ? v : "kinetic";
        }
        if (o.has("musicLead")) {
            String v = o.get("musicLead").getAsString();
            musicLead = v.equals("track") || v.equals("date") ? v : "artist";
        }
        if (o.has("artStyles")) {
            String v = o.get("artStyles").getAsString().toLowerCase(java.util.Locale.US);
            artStyles = v.matches("[a-z0-9,]{0,200}") ? v : "";
        }
        if (o.has("artCount")) {
            int c = o.get("artCount").getAsInt();
            artCount = c < 1 ? 1 : (c > 6 ? 6 : c);
        }
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("photos", photos);
        o.addProperty("art", art);
        o.addProperty("nyt", nyt);
        o.addProperty("spotify", spotify);
        o.addProperty("artCount", artCount);
        o.addProperty("artStyles", artStyles);
        o.addProperty("music", music);
        o.addProperty("musicTakeover", musicTakeover);
        o.addProperty("musicStyle", musicStyle);
        o.addProperty("musicLead", musicLead);
        o.addProperty("musicLyrics", musicLyrics);
        return o;
    }

    public void write(File dir) throws IOException {
        File tmp = new File(dir, FILE_NAME + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(toJson().toString().getBytes(UTF_8));
        } finally {
            out.close();
        }
        if (!tmp.renameTo(new File(dir, FILE_NAME))) {
            throw new IOException("could not save " + FILE_NAME);
        }
    }
}
