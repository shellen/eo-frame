package com.aphex3k.eo1;

import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Keeps the frame's own Spotify connection, so the live music page stays current whether or not
 * anyone has the phone page open. The phone page signs in once (PKCE) and hands the refresh token
 * over; from then on this polls "currently playing" every few seconds and "recently played" every
 * couple of minutes, and serves the result as {@code /music/state}. Album art is proxied and cached
 * on the frame so the display page can read its pixels for colour.
 *
 * <p>The refresh token lives in {@code files/music.json} and is never sent back out.
 */
public final class MusicService {

    public interface Listener {
        /** Called on the poll thread when playback starts or stops. */
        void onPlaybackChanged(boolean playing);
    }

    private static final String TAG = "EO1-music";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String FILE = "music.json";
    private static final String CACHE_DIR = "music-cache";
    private static final int CACHE_MAX_FILES = 60;
    private static final long POLL_PLAYING_MS = 6000;
    private static final long POLL_IDLE_MS = 15000;
    private static final long RECENT_EVERY_MS = 120000;
    private static final long JANITOR_EVERY_MS = 3600000;
    private static final Pattern ART_URL = Pattern.compile("^https://i\\.scdn\\.co/image/[A-Za-z0-9]{8,64}$");

    private static MusicService sInstance;

    public static synchronized MusicService get(File filesDir) {
        if (sInstance == null) {
            sInstance = new MusicService(filesDir);
        }
        return sInstance;
    }

    private final File dir;
    private final Object lock = new Object();
    private String clientId;
    private String refreshToken;
    private String accessToken;
    private long accessExpMs;
    private volatile boolean playing;
    private volatile JsonObject state = disconnectedState();
    private JsonArray recent = new JsonArray();
    private JsonObject last;
    private long lastRecentMs;
    private long lastJanitorMs;
    private String lastError;
    private Thread worker;
    private volatile Listener listener;
    private OkHttpClient http;

    private MusicService(File filesDir) {
        this.dir = filesDir;
        load();
        if (refreshToken != null) {
            startWorker();
        }
    }

    public void setListener(@Nullable Listener l) {
        listener = l;
    }

    public boolean isConnected() {
        synchronized (lock) {
            return refreshToken != null;
        }
    }

    public boolean isPlaying() {
        return playing;
    }

    /** True when the live music page should hold the screen right now. */
    public static boolean takeoverActive(File filesDir) {
        WallSources s = WallSources.read(filesDir);
        return s.music && s.musicTakeover && get(filesDir).isPlaying();
    }

    public String stateJson() {
        return state.toString();
    }

    // ------------------------------------------------------------------ connect / disconnect

    public void connect(String newClientId, String newRefreshToken, @Nullable String newAccessToken,
            long expiresInSec) throws IOException {
        if (newClientId == null || !newClientId.matches("[0-9a-fA-F]{32}")
                || newRefreshToken == null || newRefreshToken.length() < 20 || newRefreshToken.length() > 1024) {
            throw new IOException("invalid connection details");
        }
        synchronized (lock) {
            clientId = newClientId;
            refreshToken = newRefreshToken;
            accessToken = newAccessToken;
            accessExpMs = newAccessToken == null ? 0 : System.currentTimeMillis() + Math.max(0, expiresInSec - 60) * 1000L;
            lastRecentMs = 0;
            save();
        }
        startWorker();
        synchronized (lock) {
            lock.notifyAll();   // poll right away
        }
    }

    public void disconnect() {
        synchronized (lock) {
            clientId = null;
            refreshToken = null;
            accessToken = null;
            recent = new JsonArray();
            last = null;
            //noinspection ResultOfMethodCallIgnored
            new File(dir, FILE).delete();
            state = disconnectedState();
            lock.notifyAll();
        }
        setPlaying(false);
    }

    // ------------------------------------------------------------------ polling

    private synchronized void startWorker() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "music-poll");
        worker.setDaemon(true);
        worker.start();
    }

    private void loop() {
        while (true) {
            synchronized (lock) {
                if (refreshToken == null) {
                    worker = null;
                    return;
                }
            }
            try {
                pollOnce();
                lastError = null;
            } catch (Throwable t) {
                lastError = t.getMessage();
                Log.w(TAG, "poll failed: " + t);
                publish();
            }
            long now = System.currentTimeMillis();
            if (now - lastJanitorMs > JANITOR_EVERY_MS) {
                lastJanitorMs = now;
                try {
                    GeneratedFilesJanitor.run(dir);
                } catch (Throwable t) {
                    Log.w(TAG, "janitor failed: " + t);
                }
            }
            synchronized (lock) {
                try {
                    lock.wait(playing ? POLL_PLAYING_MS : POLL_IDLE_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void pollOnce() throws IOException {
        long now = System.currentTimeMillis();
        JsonObject current = null;
        JsonObject cp = apiGet("/me/player/currently-playing");
        if (cp != null && cp.has("item") && !cp.get("item").isJsonNull()
                && "track".equals(str(cp, "currently_playing_type"))
                && cp.has("is_playing") && cp.get("is_playing").getAsBoolean()) {
            JsonObject item = cp.getAsJsonObject("item");
            current = trackJson(item);
            current.addProperty("progressMs", cp.has("progress_ms") && !cp.get("progress_ms").isJsonNull() ? cp.get("progress_ms").getAsLong() : 0);
            current.addProperty("durationMs", item.has("duration_ms") ? item.get("duration_ms").getAsLong() : 0);
        }
        boolean nowPlaying = current != null;
        boolean wasPlaying = playing;
        if (now - lastRecentMs > RECENT_EVERY_MS || (wasPlaying && !nowPlaying)) {
            pollRecent();
            lastRecentMs = now;
        }
        synchronized (lock) {
            JsonObject s = new JsonObject();
            s.addProperty("connected", true);
            s.addProperty("playing", nowPlaying);
            s.addProperty("updatedAt", now);
            s.add("current", current);
            s.add("last", last);
            s.add("recent", recent);
            state = s;
        }
        setPlaying(nowPlaying);
    }

    private void pollRecent() throws IOException {
        JsonObject j = apiGet("/me/player/recently-played?limit=50");
        if (j == null || !j.has("items")) {
            return;
        }
        JsonArray items = j.getAsJsonArray("items");
        JsonArray albums = new JsonArray();
        Set<String> seen = new HashSet<>();
        JsonObject newest = null;
        for (JsonElement e : items) {
            JsonObject it = e.getAsJsonObject();
            if (!it.has("track") || it.get("track").isJsonNull()) {
                continue;
            }
            JsonObject t = trackJson(it.getAsJsonObject("track"));
            if (newest == null) {
                newest = t;
                newest.addProperty("playedAt", parseIso(str(it, "played_at")));
            }
            JsonObject al = it.getAsJsonObject("track").getAsJsonObject("album");
            String alId = al == null ? null : str(al, "id");
            if (alId == null || !seen.add(alId) || !t.has("art")) {
                continue;
            }
            if (albums.size() < 24) {
                JsonObject a = new JsonObject();
                a.addProperty("album", str(t, "album"));
                a.addProperty("artist", str(t, "artist"));
                a.addProperty("art", str(t, "art"));
                albums.add(a);
            }
        }
        synchronized (lock) {
            recent = albums;
            if (newest != null) {
                last = newest;
            }
        }
    }

    private void publish() {
        synchronized (lock) {
            JsonObject s = state.deepCopy();
            s.addProperty("connected", refreshToken != null);
            if (lastError != null) {
                s.addProperty("error", lastError);
            }
            state = s;
        }
    }

    private void setPlaying(boolean now) {
        boolean before = playing;
        playing = now;
        Listener l = listener;
        if (before != now && l != null) {
            try {
                l.onPlaybackChanged(now);
            } catch (Throwable t) {
                Log.w(TAG, "listener failed: " + t);
            }
        }
    }

    // ------------------------------------------------------------------ Spotify HTTP

    /**
     * Android 4.4's TLS stack only offers CBC cipher suites, and accounts.spotify.com (the token
     * endpoint) refuses them with a handshake failure. So this client runs TLS through a bundled
     * Conscrypt provider (TLS 1.3 / AES-GCM / ChaCha20), still validating against the app's merged
     * root bundle. Scoped to Spotify: the rest of the app keeps its own TLS path. If Conscrypt
     * cannot load, fall back to that path.
     */
    private OkHttpClient client() {
        if (http != null) {
            return http;
        }
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS);
        if (ModernTls.apply(b)) {
            http = b.build();
            Log.i(TAG, "Spotify TLS via Conscrypt");
        } else {
            http = ApiServiceGenerator.enableTls12OnPreLollipop(b, null).build();
        }
        return http;
    }

    /** Sets SNI and session tickets on Conscrypt sockets (the host is only known here). */
    static final class HostnameSocketFactory extends javax.net.ssl.SSLSocketFactory {
        private final javax.net.ssl.SSLSocketFactory d;

        HostnameSocketFactory(javax.net.ssl.SSLSocketFactory delegate) {
            d = delegate;
        }

        private java.net.Socket tune(java.net.Socket s, @Nullable String host) {
            if (s instanceof javax.net.ssl.SSLSocket && org.conscrypt.Conscrypt.isConscrypt((javax.net.ssl.SSLSocket) s)) {
                javax.net.ssl.SSLSocket ss = (javax.net.ssl.SSLSocket) s;
                if (host != null) {
                    org.conscrypt.Conscrypt.setHostname(ss, host);
                }
                org.conscrypt.Conscrypt.setUseSessionTickets(ss, true);
            }
            return s;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return d.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return d.getSupportedCipherSuites();
        }

        @Override
        public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose)
                throws IOException {
            return tune(d.createSocket(s, host, port, autoClose), host);
        }

        @Override
        public java.net.Socket createSocket(String host, int port) throws IOException {
            return tune(d.createSocket(host, port), host);
        }

        @Override
        public java.net.Socket createSocket(String host, int port, java.net.InetAddress local, int localPort)
                throws IOException {
            return tune(d.createSocket(host, port, local, localPort), host);
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress host, int port) throws IOException {
            return tune(d.createSocket(host, port), null);
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress local,
                int localPort) throws IOException {
            return tune(d.createSocket(address, port, local, localPort), null);
        }
    }

    @Nullable
    private JsonObject apiGet(String path) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token = token(attempt > 0);
            Request req = new Request.Builder().url("https://api.spotify.com/v1" + path)
                    .header("Authorization", "Bearer " + token).build();
            Response r = client().newCall(req).execute();
            try {
                if (r.code() == 204) {
                    return null;
                }
                if (r.code() == 401 && attempt == 0) {
                    continue;   // token expired early: refresh and retry once
                }
                if (!r.isSuccessful()) {
                    throw new IOException("Spotify " + r.code());
                }
                ResponseBody b = r.body();
                return b == null ? null : JsonParser.parseString(b.string()).getAsJsonObject();
            } finally {
                r.close();
            }
        }
        throw new IOException("Spotify refused the token");
    }

    private String token(boolean force) throws IOException {
        String cid;
        String rt;
        synchronized (lock) {
            if (!force && accessToken != null && System.currentTimeMillis() < accessExpMs) {
                return accessToken;
            }
            cid = clientId;
            rt = refreshToken;
        }
        if (rt == null) {
            throw new IOException("not connected");
        }
        Request req = new Request.Builder().url("https://accounts.spotify.com/api/token")
                .post(new FormBody.Builder().add("grant_type", "refresh_token")
                        .add("refresh_token", rt).add("client_id", cid).build()).build();
        Response r = client().newCall(req).execute();
        try {
            ResponseBody b = r.body();
            String body = b == null ? "" : b.string();
            if (!r.isSuccessful()) {
                if (r.code() == 400 && body.contains("invalid_grant")) {
                    disconnect();   // revoked or replaced: the phone page will offer Connect again
                }
                throw new IOException("token refresh failed (" + r.code() + ")");
            }
            JsonObject j = JsonParser.parseString(body).getAsJsonObject();
            synchronized (lock) {
                accessToken = str(j, "access_token");
                long exp = j.has("expires_in") ? j.get("expires_in").getAsLong() : 3600;
                accessExpMs = System.currentTimeMillis() + Math.max(60, exp - 60) * 1000L;
                String newRt = str(j, "refresh_token");
                if (newRt != null && !newRt.equals(refreshToken)) {
                    refreshToken = newRt;   // Spotify rotates these
                    save();
                }
                return accessToken;
            }
        } finally {
            r.close();
        }
    }

    // ------------------------------------------------------------------ lyrics (LRCLIB)

    private static final String LYRICS_HOST = "https://lrclib.net/api/";
    private static final String LYRICS_AGENT = "eo-frame (https://github.com/shellen/eo-frame)";
    private static final Pattern LRC_TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2}(?:[.:]\\d{1,3})?)\\]");
    private final java.util.LinkedHashMap<String, JsonObject> lyricsCache =
            new java.util.LinkedHashMap<String, JsonObject>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, JsonObject> e) {
                    return size() > 40;
                }
            };

    /**
     * Timed lyrics for a song from LRCLIB (a free, community-run lyrics database), cached in
     * memory. Only the song's artist, title, album and length are sent.
     *
     * @return {found, synced, instrumental, lines:[{t: ms, l: text}]}; found=false when LRCLIB has
     *         none, plus "error" when it couldn't be asked (not cached, so a later play retries)
     */
    public JsonObject lyrics(@Nullable String artist, @Nullable String title, @Nullable String album, long durationMs) {
        artist = artist == null ? "" : artist.trim();
        title = title == null ? "" : title.trim();
        album = album == null ? "" : album.trim();
        JsonObject out = new JsonObject();
        if (artist.isEmpty() || title.isEmpty()) {
            out.addProperty("found", false);
            return out;
        }
        String key = (artist + "\n" + title + "\n" + album + "\n" + (durationMs / 1000)).toLowerCase(Locale.US);
        synchronized (lyricsCache) {
            JsonObject hit = lyricsCache.get(key);
            if (hit != null) {
                return hit.deepCopy();
            }
        }
        try {
            JsonObject rec = null;
            long secs = Math.round(durationMs / 1000.0);
            if (!album.isEmpty() && secs > 0) {
                okhttp3.HttpUrl u = okhttp3.HttpUrl.parse(LYRICS_HOST + "get").newBuilder()
                        .addQueryParameter("artist_name", artist).addQueryParameter("track_name", title)
                        .addQueryParameter("album_name", album).addQueryParameter("duration", String.valueOf(secs)).build();
                JsonElement e = lyricsGet(u);
                if (e != null && e.isJsonObject()) {
                    rec = e.getAsJsonObject();
                }
            }
            if (rec == null || !hasSynced(rec)) {
                okhttp3.HttpUrl u = okhttp3.HttpUrl.parse(LYRICS_HOST + "search").newBuilder()
                        .addQueryParameter("artist_name", artist).addQueryParameter("track_name", title).build();
                JsonElement e = lyricsGet(u);
                JsonObject best = e != null && e.isJsonArray() ? pickLyrics(e.getAsJsonArray(), secs) : null;
                if (best != null) {
                    rec = best;
                }
            }
            out = lyricsResult(rec);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "lyrics: " + e);
            out.addProperty("found", false);
            out.addProperty("error", "couldn't reach the lyrics service");
            return out;
        }
        synchronized (lyricsCache) {
            lyricsCache.put(key, out.deepCopy());
        }
        return out;
    }

    @Nullable
    private JsonElement lyricsGet(okhttp3.HttpUrl url) throws IOException {
        Request req = new Request.Builder().url(url).header("User-Agent", LYRICS_AGENT).build();
        Response r = client().newCall(req).execute();
        try {
            if (r.code() == 404) {
                return null;
            }
            if (!r.isSuccessful()) {
                throw new IOException("lrclib " + r.code());
            }
            ResponseBody b = r.body();
            return b == null ? null : JsonParser.parseString(b.string());
        } finally {
            r.close();
        }
    }

    private static boolean hasSynced(JsonObject rec) {
        return rec.has("syncedLyrics") && rec.get("syncedLyrics").isJsonPrimitive()
                && !rec.get("syncedLyrics").getAsString().trim().isEmpty();
    }

    /** From search results: the first with timed lyrics and a length within 4 s (any length if unknown). */
    @Nullable
    static JsonObject pickLyrics(JsonArray results, long secs) {
        JsonObject instrumental = null;
        for (JsonElement e : results) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            double d = o.has("duration") && o.get("duration").isJsonPrimitive() ? o.get("duration").getAsDouble() : 0;
            if (secs > 0 && d > 0 && Math.abs(d - secs) > 4) {
                continue;
            }
            if (hasSynced(o)) {
                return o;
            }
            if (instrumental == null && o.has("instrumental") && o.get("instrumental").getAsBoolean()) {
                instrumental = o;
            }
        }
        return instrumental;
    }

    static JsonObject lyricsResult(@Nullable JsonObject rec) {
        JsonObject out = new JsonObject();
        boolean instrumental = rec != null && rec.has("instrumental") && rec.get("instrumental").isJsonPrimitive()
                && rec.get("instrumental").getAsBoolean();
        JsonArray lines = rec != null && hasSynced(rec) ? parseLrc(rec.get("syncedLyrics").getAsString()) : new JsonArray();
        out.addProperty("found", lines.size() > 0 || instrumental);
        out.addProperty("synced", lines.size() > 0);
        out.addProperty("instrumental", instrumental);
        out.add("lines", lines);
        return out;
    }

    /** "[01:02.50] words" lines → [{t: 62500, l: "words"}], sorted; a line can carry several tags. */
    static JsonArray parseLrc(String lrc) {
        java.util.List<long[]> times = new java.util.ArrayList<long[]>();
        java.util.List<String> texts = new java.util.ArrayList<String>();
        for (String raw : lrc.split("\r?\n")) {
            java.util.regex.Matcher m = LRC_TAG.matcher(raw);
            java.util.List<Long> ts = new java.util.ArrayList<Long>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long min = Long.parseLong(m.group(1));
                double sec = Double.parseDouble(m.group(2).replace(':', '.'));
                ts.add(min * 60000L + Math.round(sec * 1000));
                end = m.end();
            }
            if (ts.isEmpty()) {
                continue;
            }
            String text = raw.substring(end).trim();
            for (Long t : ts) {
                times.add(new long[]{t, texts.size()});
                texts.add(text);
            }
        }
        java.util.Collections.sort(times, new Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                return a[0] < b[0] ? -1 : (a[0] > b[0] ? 1 : Long.compare(a[1], b[1]));
            }
        });
        JsonArray out = new JsonArray();
        for (long[] t : times) {
            JsonObject o = new JsonObject();
            o.addProperty("t", t[0]);
            o.addProperty("l", texts.get((int) t[1]));
            out.add(o);
        }
        return out;
    }

    // ------------------------------------------------------------------ album art proxy

    /** Cached bytes for an album-art URL, fetching it once; null for anything not Spotify art. */
    @Nullable
    public byte[] image(String url) {
        if (url == null || !ART_URL.matcher(url).matches()) {
            return null;
        }
        File cache = new File(dir, CACHE_DIR);
        File f = new File(cache, sha1(url) + ".jpg");
        if (f.isFile() && f.length() > 0) {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            return readFile(f);
        }
        try {
            Response r = client().newCall(new Request.Builder().url(url).build()).execute();
            try {
                ResponseBody b = r.body();
                if (!r.isSuccessful() || b == null || b.contentLength() > 3_000_000) {
                    return null;
                }
                byte[] bytes = b.bytes();
                if (!cache.isDirectory() && !cache.mkdirs()) {
                    return bytes;
                }
                File tmp = new File(cache, f.getName() + ".tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(f);
                trimCache(cache);
                return bytes;
            } finally {
                r.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "art fetch failed: " + e);
            return null;
        }
    }

    /** Keeps the art cache to its newest {@link #CACHE_MAX_FILES} files. */
    static void trimCache(File cache) {
        File[] files = cache.listFiles();
        if (files == null || files.length <= CACHE_MAX_FILES) {
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (int i = CACHE_MAX_FILES; i < files.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject trackJson(JsonObject t) {
        JsonObject o = new JsonObject();
        o.addProperty("id", str(t, "id"));
        o.addProperty("title", str(t, "name"));
        StringBuilder artists = new StringBuilder();
        if (t.has("artists") && t.get("artists").isJsonArray()) {
            for (JsonElement a : t.getAsJsonArray("artists")) {
                String n = str(a.getAsJsonObject(), "name");
                if (n != null) {
                    if (artists.length() > 0) {
                        artists.append(", ");
                    }
                    artists.append(n);
                }
            }
        }
        o.addProperty("artist", artists.toString());
        JsonObject al = t.has("album") && t.get("album").isJsonObject() ? t.getAsJsonObject("album") : null;
        if (al != null) {
            o.addProperty("album", str(al, "name"));
            String art = biggestImage(al);
            if (art != null) {
                o.addProperty("art", art);
            }
        }
        return o;
    }

    @Nullable
    private static String biggestImage(JsonObject album) {
        if (!album.has("images") || !album.get("images").isJsonArray()) {
            return null;
        }
        String best = null;
        int bestW = -1;
        for (JsonElement e : album.getAsJsonArray("images")) {
            JsonObject im = e.getAsJsonObject();
            int w = im.has("width") && !im.get("width").isJsonNull() ? im.get("width").getAsInt() : 0;
            String u = str(im, "url");
            if (u != null && w > bestW && w <= 700) {
                best = u;
                bestW = w;
            }
        }
        return best;
    }

    @Nullable
    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    static long parseIso(@Nullable String s) {
        if (s == null) {
            return 0;
        }
        String[] formats = {"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        for (String f : formats) {
            try {
                SimpleDateFormat df = new SimpleDateFormat(f, Locale.US);
                df.setTimeZone(TimeZone.getTimeZone("UTC"));
                return df.parse(s).getTime();
            } catch (Exception ignored) {
                // next format
            }
        }
        return 0;
    }

    private static JsonObject disconnectedState() {
        JsonObject s = new JsonObject();
        s.addProperty("connected", false);
        s.addProperty("playing", false);
        s.addProperty("updatedAt", System.currentTimeMillis());
        s.add("current", null);
        s.add("last", null);
        s.add("recent", new JsonArray());
        return s;
    }

    private void load() {
        File f = new File(dir, FILE);
        if (!f.isFile()) {
            return;
        }
        try {
            JsonObject j = JsonParser.parseString(new String(readFile(f), UTF_8)).getAsJsonObject();
            clientId = str(j, "clientId");
            refreshToken = str(j, "refreshToken");
            if (clientId == null || refreshToken == null) {
                clientId = null;
                refreshToken = null;
            } else {
                JsonObject s = disconnectedState();
                s.addProperty("connected", true);
                state = s;
            }
        } catch (Exception e) {
            Log.w(TAG, "music.json unreadable: " + e);
        }
    }

    private void save() {
        try {
            JsonObject j = new JsonObject();
            j.addProperty("clientId", clientId);
            j.addProperty("refreshToken", refreshToken);
            File tmp = new File(dir, FILE + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(j.toString().getBytes(UTF_8));
            } finally {
                out.close();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.setReadable(false, false);
            //noinspection ResultOfMethodCallIgnored
            tmp.setReadable(true, true);
            if (!tmp.renameTo(new File(dir, FILE))) {
                Log.w(TAG, "could not save music.json");
            }
        } catch (IOException e) {
            Log.w(TAG, "could not save music.json: " + e);
        }
    }

    private static byte[] readFile(File f) {
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
            return off == b.length ? b : Arrays.copyOf(b, off);
        } catch (IOException e) {
            return new byte[0];
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format(Locale.US, "%02x", x));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
