package com.aphex3k.eo1;

import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceResponse;

import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fallback for the kiosk browser on Android 4.4, whose own TLS stack trusts only 2013's roots and
 * offers ciphers many servers no longer accept. When a host fails that way, the browser's requests
 * to it are fetched here instead, through {@link ModernTls}, and handed back to the page.
 *
 * <p>Only hosts that have already failed are routed through here, so pages that load natively
 * keep the browser's own networking. API 19 limits what an intercepted request can carry: every
 * request is sent as a GET and the response's status and headers are not passed on. That suits
 * pages meant to be looked at, which is what the frame shows.
 */
final class ModernWebFetcher {
    private static final String TAG = "EO1";
    private static final Set<String> HOSTS = new HashSet<String>();
    private static volatile OkHttpClient client;
    private static volatile boolean unavailable;
    @Nullable
    private static volatile String userAgent;

    private ModernWebFetcher() {
    }

    static void setUserAgent(@Nullable String ua) {
        userAgent = ua;
    }

    /** Whether the fallback can run at all (Conscrypt loaded). */
    static boolean available() {
        return client() != null;
    }

    /**
     * Routes {@code host} through the fallback from now on.
     *
     * @return true if it was not routed already (so a reload can help)
     */
    static boolean routeHost(@Nullable String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        synchronized (HOSTS) {
            return HOSTS.add(host.toLowerCase(Locale.ROOT));
        }
    }

    static boolean isRouted(@Nullable String host) {
        if (host == null) {
            return false;
        }
        synchronized (HOSTS) {
            return HOSTS.contains(host.toLowerCase(Locale.ROOT));
        }
    }

    /** The host of an http(s) address, or null. */
    @Nullable
    static String hostOf(@Nullable String url) {
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        int start;
        if (lower.startsWith("https://")) {
            start = 8;
        } else if (lower.startsWith("http://")) {
            start = 7;
        } else {
            return null;
        }
        int end = start;
        while (end < lower.length() && "/?#".indexOf(lower.charAt(end)) < 0) {
            end++;
        }
        String authority = lower.substring(start, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        int colon = authority.lastIndexOf(':');
        if (colon >= 0 && authority.indexOf(']') < colon) {
            authority = authority.substring(0, colon);
        }
        return authority.isEmpty() ? null : authority;
    }

    /** "text/html; charset=UTF-8" → {"text/html", "UTF-8"}; either may be null. */
    static String[] splitContentType(@Nullable String contentType) {
        if (contentType == null || contentType.trim().isEmpty()) {
            return new String[]{null, null};
        }
        String[] parts = contentType.split(";");
        String mime = parts[0].trim().toLowerCase(Locale.ROOT);
        String charset = null;
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                charset = p.substring(8).trim().replace("\"", "");
            }
        }
        return new String[]{mime.isEmpty() ? null : mime, charset};
    }

    /**
     * Called on the browser's network thread for every request. Returns null (let the browser
     * fetch it) unless the request is https to a host routed through the fallback.
     */
    @Nullable
    static WebResourceResponse intercept(@Nullable String url) {
        if (url == null || !url.regionMatches(true, 0, "https://", 0, 8) || !isRouted(hostOf(url))) {
            return null;
        }
        OkHttpClient c = client();
        if (c == null) {
            return null;
        }
        try {
            Request.Builder rb = new Request.Builder().url(url);
            String ua = userAgent;
            if (ua != null) {
                rb.header("User-Agent", ua);
            }
            CookieManager cookies = CookieManager.getInstance();
            String cookie = cookies.getCookie(url);
            if (cookie != null && !cookie.isEmpty()) {
                rb.header("Cookie", cookie);
            }
            Response r = c.newCall(rb.build()).execute();
            List<String> setCookies = r.headers("Set-Cookie");
            for (String sc : setCookies) {
                cookies.setCookie(url, sc);
            }
            ResponseBody body = r.body();
            if (body == null) {
                r.close();
                return empty();
            }
            MediaType type = body.contentType();
            String[] ct = splitContentType(type == null ? null : type.toString());
            InputStream in = body.byteStream();   // closing it releases the response
            return new WebResourceResponse(ct[0] == null ? "application/octet-stream" : ct[0], ct[1], in);
        } catch (Exception e) {
            Log.w(TAG, "web fallback: " + url + " failed: " + e);
            return empty();
        }
    }

    private static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    @Nullable
    private static OkHttpClient client() {
        if (client != null || unavailable) {
            return client;
        }
        synchronized (ModernWebFetcher.class) {
            if (client == null && !unavailable) {
                OkHttpClient.Builder b = new OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
                        .followRedirects(true)
                        .followSslRedirects(true);
                if (ModernTls.apply(b)) {
                    client = b.build();
                    Log.i(TAG, "web fallback: modern TLS ready");
                } else {
                    unavailable = true;
                }
            }
            return client;
        }
    }
}
