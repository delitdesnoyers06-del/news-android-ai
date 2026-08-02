package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link AiVec} and {@link AiText} — the two helpers everything else is built on. */
public class AiVecTextTest {

    private static final double EPS = 1e-6d;

    @Test
    public void unitReturnsNullForEveryDirectionlessInput() {
        assertNull(AiVec.unit(null));
        assertNull(AiVec.unit(new float[0]));
        assertNull("veille embed.py::_unit semantics - null, not a zero vector",
                AiVec.unit(new float[]{0f, 0f, 0f}));
    }

    @Test
    public void unitDoesNotMutateItsInput() {
        float[] in = {3f, 4f};
        float[] out = AiVec.unit(in);
        assertNotSame(in, out);
        assertEquals(3f, in[0], 1e-6f);
        assertEquals(0.6f, out[0], 1e-6f);
        assertEquals(0.8f, out[1], 1e-6f);
    }

    @Test
    public void dotIsZeroRatherThanThrowingOnMismatch() {
        assertEquals(0d, AiVec.dot(null, new float[]{1f}), EPS);
        assertEquals(0d, AiVec.dot(new float[]{1f}, null), EPS);
        assertEquals(0d, AiVec.dot(new float[]{1f}, new float[]{1f, 2f}), EPS);
        assertEquals(11d, AiVec.dot(new float[]{1f, 2f}, new float[]{3f, 4f}), EPS);
    }

    @Test
    public void addInPlaceIsTheOnlyMutation() {
        float[] acc = {1f, 1f};
        AiVec.addInPlace(acc, new float[]{2f, 3f}, +1);
        assertEquals(3f, acc[0], 1e-6f);
        AiVec.addInPlace(acc, new float[]{2f, 3f}, -1);
        assertEquals(1f, acc[0], 1e-6f);
        AiVec.addInPlace(acc, new float[]{9f}, +1);   // mismatch: no-op, no throw
        assertEquals(1f, acc[0], 1e-6f);
    }

    @Test
    public void clipNeverSplitsASurrogatePair() {
        String emoji = "ab😀cd";     // a, b, <2-char emoji>, c, d
        assertEquals("ab", AiText.clip(emoji, 3));   // would have cut the high surrogate
        assertEquals("ab😀", AiText.clip(emoji, 4));
    }

    @Test
    public void clipHandlesTheDegenerateInputs() {
        assertEquals("", AiText.clip(null, 10));
        assertEquals("", AiText.clip("abc", 0));
        assertEquals("abc", AiText.clip("abc", 99));
    }

    @Test
    public void sanitiseStripsTagsEntitiesAndWhitespace() {
        assertEquals("Hello & world",
                AiText.sanitise("  <b>Hello</b>\n&amp;\tworld  "));
        assertEquals("", AiText.sanitise(null));
    }

    @Test
    public void theEmbeddingInputIsTitleThenBodyClippedTo1400() {
        assertEquals(1400, AiText.EMBED_CLIP_CHARS);

        assertEquals("Title\nBody", AiText.embedInput("Title", "Body"));
        assertEquals("a title cannot be swallowed by an empty body",
                "Title", AiText.embedInput("Title", null));
        assertEquals("Body", AiText.embedInput("", "Body"));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            sb.append("0123456789");
        }
        assertTrue("the [1,512] INT32 graph input must never be overflowed",
                AiText.embedInput("t", sb.toString()).length() <= AiText.EMBED_CLIP_CHARS);
    }

    @Test
    public void theTitleSnapshotIsBoundedAndSanitised() {
        assertEquals("Hi there", AiText.titleSnap("<i>Hi</i>\n there"));
        assertEquals("", AiText.titleSnap(null));
    }
}
