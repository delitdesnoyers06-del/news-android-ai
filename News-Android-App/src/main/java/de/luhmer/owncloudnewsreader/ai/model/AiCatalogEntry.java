package de.luhmer.owncloudnewsreader.ai.model;

/**
 * One row of {@code res/raw/ai_model_catalog.json} — what <i>could</i> be installed. What
 * <i>is</i> installed lives in {@code AI_MODEL} ({@code AiModelRegistry}).
 *
 * <p>Plain public fields and a no-arg constructor: this is a Gson DTO and
 * {@code proguard-rules.pro} keeps it by name.</p>
 *
 * <p><b>{@link #minTotalRamBytes} is data, never a formula</b> (PLAN D24). A size-derived guess
 * (&times;1.4 + 1&nbsp;GB) is exactly the sort of number that turns out wrong on real hardware; as a
 * catalogue field the device spike replaces it without touching a line of code.</p>
 */
public class AiCatalogEntry {

    public static final String PURPOSE_LLM = "llm";
    public static final String PURPOSE_EMBEDDER = "embedder";
    /** Neural text-to-speech, run by sherpa-onnx in the {@code mlGemma} flavor. */
    public static final String PURPOSE_TTS = "tts";

    public static final String SOURCE_HF = "hf";
    public static final String SOURCE_URL = "url";

    // ---- TTS engine kinds (only meaningful when purpose == PURPOSE_TTS) --------------------
    public static final String TTS_ENGINE_VITS = "vits";
    public static final String TTS_ENGINE_KOKORO = "kokoro";
    public static final String TTS_ENGINE_MATCHA = "matcha";

    /** A single extra file a model needs next to its archive, e.g. a Matcha vocoder. */
    public static class Companion {
        /** Full https URL of the file. */
        public String url;
        /** Name to save it as, inside the unpacked model root. */
        public String fileName;
        public long sizeBytes;
    }

    /** Stable id. This is what {@code sp_ai_model_*} and {@code AI_MODEL.MODEL_ID} store. */
    public String id;
    public String displayName;
    /** {@link #PURPOSE_LLM} or {@link #PURPOSE_EMBEDDER}. */
    public String purpose;
    /** {@link #SOURCE_HF} (Hugging Face) or {@link #SOURCE_URL} (a plain URL, the MediaPipe CDN). */
    public String source;
    /** {@code "litert-community/gemma-4-E2B-it-litert-lm"}; {@code source=hf} only. */
    public String repo;
    /** {@code "main"} or a pinned commit sha. Becomes the on-disk {@code <revision>} directory. */
    public String revision;
    public String fileName;
    /** Full URL; {@code source=url} only. */
    public String url;
    public long sizeBytes;
    /** LFS oid. {@code null} for gated repos (HF masks the oid) and for the CDN embedder. */
    public String sha256;
    /** The CDN embedder's {@code etag}. {@code null} everywhere else. */
    public String md5;
    /** {@code true} =&gt; anonymous GET answers <b>401 GatedRepo</b>; a token is mandatory (D22). */
    public boolean gated;
    /** Drives {@code AiLlm.supportsConstrainedDecoding()} until the on-device probe overrides it. */
    public boolean sentencePiece;
    /** {@code SELECT_OK} threshold against {@code ActivityManager.MemoryInfo.totalMem}. */
    public long minTotalRamBytes;
    public int defaultMaxNumTokens;
    public String licenseUrl;

    // ---- TTS-only fields (null/0 for every other purpose) ---------------------------------
    /** {@link #TTS_ENGINE_VITS}, {@link #TTS_ENGINE_KOKORO} or {@link #TTS_ENGINE_MATCHA}. */
    public String ttsEngine;
    /** BCP-47 base language of a TTS voice, e.g. {@code "en"}, {@code "fr"}. Drives auto-selection. */
    public String lang;
    /** Number of selectable speakers/voices the model ships with. */
    public int numSpeakers;
    /** Extra files fetched next to the archive after it is unpacked (e.g. a Matcha vocoder). */
    public java.util.List<Companion> companions;

    public boolean isLlm() {
        return PURPOSE_LLM.equals(purpose);
    }

    public boolean isEmbedder() {
        return PURPOSE_EMBEDDER.equals(purpose);
    }

    public boolean isTts() {
        return PURPOSE_TTS.equals(purpose);
    }

    /** True when {@link #fileName} is a {@code .tar.bz2} archive that must be unpacked. */
    public boolean isArchive() {
        return fileName != null && fileName.endsWith(".tar.bz2");
    }

    /**
     * The directory name the sherpa-onnx archive unpacks into: its own file name with the
     * {@code .tar.bz2} suffix removed. {@code kokoro-int8-multi-lang-v1_1.tar.bz2} unpacks into a
     * {@code kokoro-int8-multi-lang-v1_1/} folder.
     */
    public String unpackRootName() {
        if (fileName == null) {
            return "";
        }
        return isArchive() ? fileName.substring(0, fileName.length() - ".tar.bz2".length()) : fileName;
    }

    /**
     * The URL to request. For Hugging Face this is deliberately the {@code resolve/main/...} form
     * and <b>never</b> the CDN redirect target: that one is signed with {@code Expires=} ~15 min, so
     * a resume after a lunch break would 403 from CloudFront (PLAN D23). Re-resolve every attempt.
     */
    public String downloadUrl() {
        if (SOURCE_URL.equals(source)) {
            return url;
        }
        String rev = revision == null || revision.isEmpty() ? "main" : revision;
        return "https://huggingface.co/" + repo + "/resolve/" + rev + "/" + fileName;
    }

    /** True when the catalogue can prove the bytes are the right bytes. */
    public boolean hasChecksum() {
        return notEmpty(sha256) || notEmpty(md5);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
