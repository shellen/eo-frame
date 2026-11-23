package com.aphex3k.eo1;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * How each web-page source last went on the wall, so the phone page can say "couldn't load on the
 * frame" instead of the page silently being skipped. Keyed by backend id; kept in memory only.
 */
final class WebPageStatus {
    private static final Map<String, JsonObject> BY_BACKEND = new HashMap<String, JsonObject>();

    private WebPageStatus() {
    }

    /** The backend id part of an asset key ("web-1:uuid" → "web-1"). */
    static String backendOf(String assetKey) {
        if (assetKey == null) {
            return "";
        }
        int c = assetKey.indexOf(':');
        return c < 0 ? assetKey : assetKey.substring(0, c);
    }

    static void rendered(String assetKey, long nowMs) {
        put(assetKey, true, null, nowMs);
    }

    static void failed(String assetKey, String reason, long nowMs) {
        put(assetKey, false, reason, nowMs);
    }

    /** Loaded, but probably not as intended (for example a blank page). */
    static void warn(String assetKey, String reason, long nowMs) {
        put(assetKey, true, reason, nowMs);
    }

    private static void put(String assetKey, boolean ok, String reason, long nowMs) {
        String id = backendOf(assetKey);
        if (id.isEmpty()) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        if (reason != null) {
            o.addProperty(ok ? "warning" : "error", reason);
        }
        o.addProperty("atMs", nowMs);
        synchronized (BY_BACKEND) {
            BY_BACKEND.put(id, o);
        }
    }

    /** The last result for {@code backendId}, or null when it has not come up yet. */
    static JsonObject get(String backendId) {
        synchronized (BY_BACKEND) {
            JsonObject o = BY_BACKEND.get(backendId);
            return o == null ? null : o.deepCopy();
        }
    }

    /** Words a person can act on, from the browser's error code and description. */
    static String friendlyReason(int errorCode, String description) {
        switch (errorCode) {
            case -11: // ERROR_FAILED_SSL_HANDSHAKE
                return "couldn't make a secure connection";
            case -2:  // ERROR_HOST_LOOKUP
                return "couldn't find that address";
            case -6:  // ERROR_CONNECT
            case -8:  // ERROR_TIMEOUT
                return "the site didn't answer";
            default:
                return description == null || description.isEmpty() ? "the page failed to load" : description;
        }
    }

    static void clearForTest() {
        synchronized (BY_BACKEND) {
            BY_BACKEND.clear();
        }
    }
}
