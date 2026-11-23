package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

public class LyricsTest {

    @Test
    public void parsesTimedLinesInOrder() {
        JsonArray a = MusicService.parseLrc("[ar:Someone]\n[00:12.50] First line\n[00:05.00]Intro\n[01:02.345]Later\n[00:20.00]\nnot a lyric");
        assertEquals(4, a.size());
        assertEquals(5000, a.get(0).getAsJsonObject().get("t").getAsLong());
        assertEquals("Intro", a.get(0).getAsJsonObject().get("l").getAsString());
        assertEquals(12500, a.get(1).getAsJsonObject().get("t").getAsLong());
        assertEquals("First line", a.get(1).getAsJsonObject().get("l").getAsString());
        assertEquals("", a.get(2).getAsJsonObject().get("l").getAsString());
        assertEquals(62345, a.get(3).getAsJsonObject().get("t").getAsLong());
    }

    @Test
    public void lineWithSeveralTagsRepeats() {
        JsonArray a = MusicService.parseLrc("[00:10.00][00:30.00]Chorus\r\n[00:20.00]Verse");
        assertEquals(3, a.size());
        assertEquals("Chorus", a.get(0).getAsJsonObject().get("l").getAsString());
        assertEquals("Verse", a.get(1).getAsJsonObject().get("l").getAsString());
        assertEquals(30000, a.get(2).getAsJsonObject().get("t").getAsLong());
    }

    @Test
    public void picksSearchResultByLength() {
        JsonArray r = JsonParser.parseString("[{\"duration\":300,\"syncedLyrics\":\"[00:01.00]wrong\"},"
                + "{\"duration\":201,\"syncedLyrics\":\"\"},"
                + "{\"duration\":202,\"syncedLyrics\":\"[00:01.00]right\"}]").getAsJsonArray();
        JsonObject o = MusicService.pickLyrics(r, 200);
        assertTrue(o.get("syncedLyrics").getAsString().contains("right"));
        assertNull(MusicService.pickLyrics(JsonParser.parseString("[{\"duration\":100,\"syncedLyrics\":\"[00:01.00]x\"}]").getAsJsonArray(), 200));
    }

    @Test
    public void resultShapes() {
        JsonObject none = MusicService.lyricsResult(null);
        assertFalse(none.get("found").getAsBoolean());
        JsonObject inst = MusicService.lyricsResult(JsonParser.parseString("{\"instrumental\":true,\"syncedLyrics\":null}").getAsJsonObject());
        assertTrue(inst.get("found").getAsBoolean());
        assertFalse(inst.get("synced").getAsBoolean());
        JsonObject ok = MusicService.lyricsResult(JsonParser.parseString("{\"syncedLyrics\":\"[00:01.00]hi\"}").getAsJsonObject());
        assertTrue(ok.get("synced").getAsBoolean());
        assertEquals(1, ok.getAsJsonArray("lines").size());
    }

    @Test
    public void lyricsSettingRoundTrips() {
        WallSources w = new WallSources();
        assertFalse(w.musicLyrics);
        w.apply(JsonParser.parseString("{\"musicLyrics\":true}").getAsJsonObject());
        assertTrue(w.musicLyrics);
        assertTrue(w.toJson().get("musicLyrics").getAsBoolean());
    }
}
