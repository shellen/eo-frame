package com.aphex3k.eo1;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Before;
import org.junit.Test;

public class WebPageStatusTest {

    @Before
    public void reset() {
        WebPageStatus.clearForTest();
    }

    @Test
    public void recordsByBackendFromAssetKey() {
        assertEquals("web-1", WebPageStatus.backendOf("web-1:2f1c-uuid"));
        WebPageStatus.failed("web-1:2f1c-uuid", "couldn't make a secure connection", 1000L);
        JsonObject o = WebPageStatus.get("web-1");
        assertFalse(o.get("ok").getAsBoolean());
        assertEquals("couldn't make a secure connection", o.get("error").getAsString());
        assertEquals(1000L, o.get("atMs").getAsLong());
        assertNull(WebPageStatus.get("web-2"));
    }

    @Test
    public void laterResultReplacesEarlier() {
        WebPageStatus.failed("web-1:a", "x", 1L);
        WebPageStatus.rendered("web-1:a", 2L);
        JsonObject o = WebPageStatus.get("web-1");
        assertTrue(o.get("ok").getAsBoolean());
        assertFalse(o.has("error"));
        WebPageStatus.warn("web-1:a", "loaded but showed nothing", 3L);
        o = WebPageStatus.get("web-1");
        assertTrue(o.get("ok").getAsBoolean());
        assertEquals("loaded but showed nothing", o.get("warning").getAsString());
    }

    @Test
    public void ignoresEmptyKeys() {
        WebPageStatus.rendered("", 1L);
        WebPageStatus.rendered(null, 1L);
        assertNull(WebPageStatus.get(""));
    }

    @Test
    public void friendlyReasons() {
        assertEquals("couldn't make a secure connection", WebPageStatus.friendlyReason(-11, "net::ERR_SSL"));
        assertEquals("couldn't find that address", WebPageStatus.friendlyReason(-2, "x"));
        assertEquals("something odd", WebPageStatus.friendlyReason(-1, "something odd"));
        assertEquals("the page failed to load", WebPageStatus.friendlyReason(-1, null));
    }

    @Test
    public void fetcherHostParsing() {
        assertEquals("example.com", ModernWebFetcher.hostOf("https://Example.com/a?b#c"));
        assertEquals("example.com", ModernWebFetcher.hostOf("https://user:pw@example.com:8443/x"));
        assertEquals("192.168.1.2", ModernWebFetcher.hostOf("http://192.168.1.2:8080"));
        assertNull(ModernWebFetcher.hostOf("data:text/html,hi"));
        assertNull(ModernWebFetcher.hostOf(null));
    }

    @Test
    public void fetcherRoutesOnlyOnce() {
        assertTrue(ModernWebFetcher.routeHost("Test-Routing.example"));
        assertFalse(ModernWebFetcher.routeHost("test-routing.example"));
        assertTrue(ModernWebFetcher.isRouted("TEST-routing.example"));
        assertFalse(ModernWebFetcher.isRouted("other.example"));
        assertFalse(ModernWebFetcher.routeHost(""));
    }

    @Test
    public void contentTypeSplitting() {
        assertArrayEquals(new String[]{"text/html", "UTF-8"}, ModernWebFetcher.splitContentType("text/html; charset=\"UTF-8\""));
        assertArrayEquals(new String[]{"image/png", null}, ModernWebFetcher.splitContentType("image/png"));
        assertArrayEquals(new String[]{null, null}, ModernWebFetcher.splitContentType(null));
    }
}
