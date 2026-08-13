package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import de.luhmer.owncloudnewsreader.ai.download.AiModelDownloader;
import de.luhmer.owncloudnewsreader.ai.model.AiCatalogEntry;
import de.luhmer.owncloudnewsreader.ai.model.AiPartMeta;

/**
 * The resume sidecar, and the integrity check that decides whether a 2.6 GB download counted.
 *
 * <p>The rule these tests exist to pin: <b>a sidecar that no longer describes the catalogue entry
 * means the bytes on disk are a prefix of a different file</b>, and resuming onto them produces a
 * plausible-looking model that fails at load time, days later, with nothing pointing back here.
 */
public class AiPartMetaTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static AiCatalogEntry entry() {
        AiCatalogEntry e = new AiCatalogEntry();
        e.id = "m";
        e.source = AiCatalogEntry.SOURCE_HF;
        e.repo = "org/repo";
        e.revision = "main";
        e.fileName = "weights.litertlm";
        e.sizeBytes = 1234L;
        e.sha256 = "abc";
        return e;
    }

    @Test
    public void sidecarNameIsDerivedFromThePartFile() {
        File part = new File("/tmp/x/weights.litertlm.part");
        assertEquals("/tmp/x/weights.litertlm.meta",
                AiPartMeta.sidecarFor(part).getAbsolutePath());
    }

    @Test
    public void roundTrips() throws IOException {
        File f = tmp.newFile("a.meta");
        AiPartMeta m = new AiPartMeta();
        m.modelId = "m";
        m.url = "https://huggingface.co/org/repo/resolve/main/weights.litertlm";
        m.size = 1234L;
        m.received = 500L;
        m.sha256 = "abc";
        m.startedAt = 42L;
        assertTrue(m.write(f));

        AiPartMeta back = AiPartMeta.read(f);
        assertNotNull(back);
        assertEquals("m", back.modelId);
        assertEquals(500L, back.received);
        assertEquals(m.url, back.url);
        assertTrue("the persisted URL is the origin form, never the signed CDN redirect",
                back.url.contains("/resolve/main/"));
    }

    @Test
    public void aMissingOrUnreadableSidecarIsNullNotAnException() throws IOException {
        assertNull(AiPartMeta.read(null));
        assertNull(AiPartMeta.read(new File(tmp.getRoot(), "nope.meta")));
        File junk = tmp.newFile("junk.meta");
        try (FileOutputStream out = new FileOutputStream(junk)) {
            out.write(new byte[]{0x00, (byte) 0xFF, 0x13});
        }
        assertNull(AiPartMeta.read(junk));
    }

    @Test
    public void aChangedSizeOrChecksumInvalidatesThePartialDownload() {
        AiPartMeta m = new AiPartMeta();
        m.modelId = "m";
        m.size = 1234L;
        m.sha256 = "abc";
        assertTrue(m.matches(entry()));

        m.size = 9999L;
        assertFalse("the file changed upstream; the bytes we hold are garbage",
                m.matches(entry()));

        m.size = 1234L;
        m.sha256 = "different";
        assertFalse(m.matches(entry()));

        m.sha256 = "abc";
        m.modelId = "other";
        assertFalse(m.matches(entry()));
    }

    // ---- integrity ------------------------------------------------------------------------

    @Test
    public void sha256IsCheckedWhenThecatalogueHasOne() throws IOException {
        File f = tmp.newFile("blob");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("hello".getBytes("UTF-8"));
        }
        AiCatalogEntry e = entry();
        e.sha256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
        assertNull(AiModelDownloader.verify(f, e));

        e.sha256 = "0000000000000000000000000000000000000000000000000000000000000000";
        assertNotNull("a mismatch must be reported, not shrugged off",
                AiModelDownloader.verify(f, e));
    }

    @Test
    public void md5IsCheckedWhenThereIsNoSha256() throws IOException {
        File f = tmp.newFile("blob2");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("hello".getBytes("UTF-8"));
        }
        AiCatalogEntry e = entry();
        e.sha256 = null;
        e.md5 = "5d41402abc4b2a76b9719d911017c592";
        assertNull(AiModelDownloader.verify(f, e));
        e.md5 = "00000000000000000000000000000000";
        assertNotNull(AiModelDownloader.verify(f, e));
    }

    /**
     * A gated repo masks its LFS oid, so there is no checksum to check. We accept on size alone and
     * say so, rather than pretending the file was verified.
     */
    @Test
    public void anEntryWithNoChecksumIsAcceptedOnSizeAlone() throws IOException {
        File f = tmp.newFile("blob3");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write("hello".getBytes("UTF-8"));
        }
        AiCatalogEntry e = entry();
        e.sha256 = null;
        e.md5 = null;
        assertFalse(e.hasChecksum());
        assertNull(AiModelDownloader.verify(f, e));
    }
}
