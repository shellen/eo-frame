package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

public class BackendChangeTest {

    private static JsonObject cfg() {
        return JsonParser.parseString("{\"backends\":[{\"type\":\"local\",\"id\":\"local\"},"
                + "{\"type\":\"web\",\"id\":\"art-1\",\"host\":\"http://127.0.0.1:8080/art/\"},"
                + "{\"type\":\"web\",\"id\":\"music-1\",\"host\":\"http://127.0.0.1:8080/music/\"}]}").getAsJsonObject();
    }

    private static JsonObject change(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject find(JsonObject cfg, String id) {
        JsonArray a = cfg.getAsJsonArray("backends");
        for (int i = 0; i < a.size(); i++) {
            if (id.equals(a.get(i).getAsJsonObject().get("id").getAsString())) {
                return a.get(i).getAsJsonObject();
            }
        }
        return null;
    }

    @Test
    public void addsImmichFlickrAndWebWithFreshIds() {
        JsonObject c = cfg();
        assertNull(WebServer.applyBackendChange(c, change("{\"op\":\"add\",\"type\":\"immich\",\"host\":\"http://192.168.1.20:2283\",\"userid\":\"f@x.com\",\"password\":\"pw\"}")));
        assertNull(WebServer.applyBackendChange(c, change("{\"op\":\"add\",\"type\":\"immich\",\"host\":\"https://photos.example.com/\",\"userid\":\"g@x.com\",\"password\":\"pw\"}")));
        assertNull(WebServer.applyBackendChange(c, change("{\"op\":\"add\",\"type\":\"flickr\",\"apiKey\":\"0123456789abcdef0123456789abcdef\",\"source\":\"https://www.flickr.com/photos/someone/\"}")));
        assertNull(WebServer.applyBackendChange(c, change("{\"op\":\"add\",\"type\":\"web\",\"host\":\"http://192.168.1.20/dash/\"}")));
        JsonObject im = find(c, "immich-1");
        assertEquals("http://192.168.1.20:2283/", im.get("host").getAsString());   // trailing slash added
        assertEquals("auto", im.get("apiVersion").getAsString());
        assertNotNull(find(c, "immich-2"));
        assertNotNull(find(c, "flickr-1"));
        assertNotNull(find(c, "web-1"));
        assertTrue(find(c, "web-1").get("enabled").getAsBoolean());
    }

    @Test
    public void rejectsIncompleteOrOddInput() {
        JsonObject c = cfg();
        assertNotNull(WebServer.applyBackendChange(c, change("{\"op\":\"add\",\"type\":\"immich\",\"host\":\"192.168.1.20\",\"userid\":\"a\",\"password\":\"b\"}")));
        assertNotNull(WebServer.applyBackendChange(c, change("{\"op\":\"add\",\"type\":\"immich\",\"host\":\"http://h/\",\"userid\":\"\",\"password\":\"b\"}")));
        assertNotNull(WebServer.applyBackendChange(c, change("{\"op\":\"add\",\"type\":\"flickr\",\"apiKey\":\"short\",\"source\":\"https://www.flickr.com/photos/x/\"}")));
        assertNotNull(WebServer.applyBackendChange(c, change("{\"op\":\"add\",\"type\":\"flickr\",\"apiKey\":\"0123456789abcdef0123456789abcdef\",\"source\":\"https://evil.example/\"}")));
        assertNotNull(WebServer.applyBackendChange(c, change("{\"op\":\"add\",\"type\":\"web\",\"host\":\"javascript:alert(1)\"}")));
        assertNotNull(WebServer.applyBackendChange(c, change("{\"op\":\"add\",\"type\":\"local\"}")));
        assertNotNull(WebServer.applyBackendChange(c, change("{\"op\":\"wipe\"}")));
        assertEquals(3, c.getAsJsonArray("backends").size());
    }

    @Test
    public void framesOwnEntriesCannotBeChanged() {
        JsonObject c = cfg();
        for (String id : new String[]{"local", "art-1", "art-keep-sunset-abc", "music-1", ""}) {
            assertNotNull(id, WebServer.applyBackendChange(c, change("{\"op\":\"remove\",\"id\":\"" + id + "\"}")));
            assertNotNull(id, WebServer.applyBackendChange(c, change("{\"op\":\"enable\",\"id\":\"" + id + "\",\"enabled\":false}")));
        }
        assertEquals(3, c.getAsJsonArray("backends").size());
    }

    @Test
    public void switchesAndRemovesAddedSources() {
        JsonObject c = cfg();
        WebServer.applyBackendChange(c, change("{\"op\":\"add\",\"type\":\"web\",\"host\":\"http://h/\"}"));
        assertNull(WebServer.applyBackendChange(c, change("{\"op\":\"enable\",\"id\":\"web-1\",\"enabled\":false}")));
        assertFalse(find(c, "web-1").get("enabled").getAsBoolean());
        assertNull(WebServer.applyBackendChange(c, change("{\"op\":\"remove\",\"id\":\"web-1\"}")));
        assertNull(find(c, "web-1"));
        assertNotNull(WebServer.applyBackendChange(c, change("{\"op\":\"remove\",\"id\":\"web-1\"}")));
    }
}
