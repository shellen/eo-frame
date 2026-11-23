package com.aphex3k.eo1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class GeneratedFilesJanitorTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private File files;
    private File up;

    @Before
    public void setUp() {
        files = tmp.getRoot();
        up = new File(files, UploadedMedia.DIR_NAME);
        assertTrue(up.mkdirs());
    }

    private File make(File dir, String name, long ageMs) throws IOException {
        File f = new File(dir, name);
        FileOutputStream o = new FileOutputStream(f);
        o.write(new byte[]{1, 2, 3});
        o.close();
        assertTrue(f.setLastModified(System.currentTimeMillis() - ageMs));
        return f;
    }

    @Test
    public void keepsOnlyNewestOfEachGeneratedKind() throws IOException {
        File oldNyt = make(up, "nyt-frontpage-2026-11-19-gif.gif", 3 * 86400000L);
        File oldNytNote = make(up, "nyt-frontpage-2026-11-19-gif.gif.incompat.txt", 3 * 86400000L);
        File midNyt = make(up, "nyt-frontpage-2026-11-20-clean.jpg", 2 * 86400000L);
        File newNyt = make(up, "nyt-frontpage-2026-11-21-gif.gif", 1000);
        File oldGrid = make(up, "spotify-grid-2026-11-20-12.jpg", 86400000L);
        File newGrid = make(up, "spotify-grid-2026-11-21-20.gif", 1000);
        File poster = make(up, "spotify-nowplaying-abc.jpg", 5000);
        File photo = make(up, "IMG_1.jpg", 9 * 86400000L);
        File kept = make(up, "art-blocks-1.jpg", 9 * 86400000L);

        assertEquals(4, GeneratedFilesJanitor.run(files));
        assertFalse(oldNyt.exists());
        assertFalse(oldNytNote.exists());
        assertFalse(midNyt.exists());
        assertTrue(newNyt.exists());
        assertFalse(oldGrid.exists());
        assertTrue(newGrid.exists());
        assertTrue(poster.exists());
        assertTrue(photo.exists());   // the user's photos are never touched
        assertTrue(kept.exists());    // nor kept art
        assertEquals(0, GeneratedFilesJanitor.run(files));   // idempotent
    }

    @Test
    public void removesOrphansAndStaleTemp() throws IOException {
        File orphanNote = make(up, "gone.jpg.incompat.txt", 1000);
        File photo = make(up, "IMG_2.jpg", 1000);
        File note = make(up, "IMG_2.jpg.incompat.txt", 1000);
        File staleTemp = new File(up, "incoming_123");
        assertTrue(staleTemp.mkdirs());
        make(staleTemp, "part0", 1000);
        assertTrue(staleTemp.setLastModified(System.currentTimeMillis() - 2 * 3600000L));
        File liveTemp = new File(up, "incoming_456");
        assertTrue(liveTemp.mkdirs());

        File thumbs = new File(files, "thumbs");
        assertTrue(thumbs.mkdirs());
        File liveThumb = make(thumbs, WebServer.thumbKey("IMG_2.jpg") + "_abc.jpg", 1000);
        File deadThumb = make(thumbs, WebServer.thumbKey("deleted.jpg") + "_abc.jpg", 1000);

        GeneratedFilesJanitor.run(files);
        assertFalse(orphanNote.exists());
        assertTrue(photo.exists());
        assertTrue(note.exists());
        assertFalse(staleTemp.exists());
        assertTrue(liveTemp.exists());
        assertTrue(liveThumb.exists());
        assertFalse(deadThumb.exists());
    }

    @Test
    public void trimsTheArtCache() throws IOException {
        File cache = new File(files, "music-cache");
        assertTrue(cache.mkdirs());
        for (int i = 0; i < 75; i++) {
            make(cache, "a" + i + ".jpg", i * 1000L);
        }
        GeneratedFilesJanitor.run(files);
        assertEquals(60, cache.list().length);
        assertTrue(new File(cache, "a0.jpg").exists());     // newest kept
        assertFalse(new File(cache, "a74.jpg").exists());   // oldest gone
    }
}
