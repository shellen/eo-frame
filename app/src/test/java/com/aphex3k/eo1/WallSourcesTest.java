package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class WallSourcesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void kindsFollowNamePrefixes() {
        assertEquals("nyt", WallSources.kindOf("nyt-frontpage-2026-11-21-gif.gif"));
        assertEquals("spotify", WallSources.kindOf("spotify-grid-2026-11-21-12.jpg"));
        assertEquals("spotify", WallSources.kindOf("spotify-nowplaying-abc.jpg"));
        assertEquals("art", WallSources.kindOf("art-striped-sunset-9q1bql.jpg"));
        assertEquals("photos", WallSources.kindOf("IMG_9647_mwr7kuh5.jpg"));
        assertEquals("photos", WallSources.kindOf("my-nyt-trip.jpg"));
    }

    @Test
    public void defaultsShowEverythingButLiveArt() {
        WallSources s = WallSources.read(tmp.getRoot());
        assertTrue(s.allows("IMG_1.jpg"));
        assertTrue(s.allows("nyt-frontpage-2026-11-21-clean.jpg"));
        assertTrue(s.allows("spotify-grid-x.jpg"));
        assertFalse(s.art);
    }

    @Test
    public void writeReadRoundTripAndFiltering() throws Exception {
        File dir = tmp.getRoot();
        WallSources s = new WallSources();
        s.apply(JsonParser.parseString("{\"photos\":false,\"art\":true,\"nyt\":false,\"artCount\":9,\"artStyles\":\"sunset,contours\"}").getAsJsonObject());
        s.write(dir);
        WallSources r = WallSources.read(dir);
        assertFalse(r.allows("IMG_1.jpg"));
        assertFalse(r.allows("nyt-frontpage-2026-11-21-clean.jpg"));
        assertTrue(r.allows("spotify-grid-x.jpg"));
        assertFalse(r.allows("art-blocks-1.jpg"));   // kept art replays live; its still is only a record
        assertEquals(6, r.artCount);                 // clamped
        assertEquals("sunset,contours", r.artStyles);
    }

    @Test
    public void keptArtRecordsReplayTheSameStyleAndSeed() {
        WallSources.KeptArt k = WallSources.keptArtFor("art-sunset-9q1bql.jpg");
        assertEquals("sunset", k.style);
        assertEquals(Long.parseLong("9q1bql", 36), k.seed);
        assertEquals("art-keep-sunset-9q1bql", k.backendId());
        assertEquals("http://127.0.0.1:8080/art/?style=sunset&seed=" + k.seed, k.url(8080));
        // files kept before the rename used the style's display name
        assertEquals("sunset", WallSources.keptArtFor("art-striped-sunset-9q1bql.jpg").style);
        assertEquals("pointillist2", WallSources.keptArtFor("art-drifting-dots-ee3jy2.jpg").style);
        assertEquals(null, WallSources.keptArtFor("IMG_1.jpg"));
        assertEquals(null, WallSources.keptArtFor("art-.jpg"));
        assertEquals(null, WallSources.keptArtFor("art-two-words-unknown-abc.jpg"));
    }

    @Test
    public void rejectsOddStyleLists() {
        WallSources s = new WallSources();
        s.apply(JsonParser.parseString("{\"artStyles\":\"sunset&x=<script>\"}").getAsJsonObject());
        assertEquals("", s.artStyles);
    }
}
