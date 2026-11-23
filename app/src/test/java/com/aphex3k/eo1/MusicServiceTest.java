package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

public class MusicServiceTest {

    @Test
    public void parsesSpotifyTimestamps() {
        assertEquals(1732250000123L, MusicService.parseIso("2024-11-22T04:33:20.123Z"));
        assertEquals(1732250000000L, MusicService.parseIso("2024-11-22T04:33:20Z"));
        assertEquals(0L, MusicService.parseIso("nonsense"));
        assertEquals(0L, MusicService.parseIso(null));
    }

    @Test
    public void onlyProxiesSpotifyArtAndStartsDisconnected() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("music").toFile();
        MusicService m = MusicService.get(dir);
        assertFalse(m.isConnected());
        assertFalse(m.isPlaying());
        assertTrue(m.stateJson().contains("\"connected\":false"));
        assertNull(m.image("http://evil.example/x.jpg"));
        assertNull(m.image("https://i.scdn.co/image/../../etc/passwd"));
        assertNull(m.image(null));
    }
}
