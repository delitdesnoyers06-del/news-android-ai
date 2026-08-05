package de.luhmer.owncloudnewsreader.ai.engine.impl;

import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import de.luhmer.owncloudnewsreader.ai.engine.AiException;
import de.luhmer.owncloudnewsreader.ai.engine.AiPcm;
import de.luhmer.owncloudnewsreader.ai.engine.AiTts;
import de.luhmer.owncloudnewsreader.ai.engine.AiTtsSpec;
import de.luhmer.owncloudnewsreader.ai.model.AiCatalogEntry;

/**
 * The {@code mlGemma} neural-TTS runtime: a thin wrapper over sherpa-onnx {@code OfflineTts}.
 *
 * <p><b>Dependency.</b> sherpa-onnx has no Maven Central artifact, so its Android AAR is bundled in
 * {@code News-Android-App/libs/} and consumed by the {@code mlGemma} flavor only (see
 * {@code libs/README.md} for the exact version to drop in). This class is written against the
 * {@code com.k2fsa.sherpa.onnx} Kotlin API; the field/constructor shapes below are pinned to that
 * README's version.</p>
 *
 * <p><b>Why {@code generate} and not {@code generateWithCallback}.</b> The callback form takes a
 * Kotlin {@code (FloatArray)->Int} lambda that is awkward to implement from Java; the article is
 * already streamed at sentence granularity by {@code AiTtsPlaybackService}, so a blocking
 * whole-chunk {@link OfflineTts#generate} per sentence is both simpler and enough.</p>
 */
public final class SherpaTts implements AiTts {

    private static final String TAG = "SherpaTts";
    private static final int NUM_THREADS = 2;

    private final OfflineTts tts;

    private SherpaTts(OfflineTts tts) {
        this.tts = tts;
    }

    static SherpaTts open(AiTtsSpec spec) throws AiException {
        if (spec == null || spec.unpackedRoot == null || !spec.unpackedRoot.isDirectory()) {
            throw new AiException(AiException.Kind.NOT_INSTALLED, "voice not unpacked");
        }
        try {
            OfflineTtsConfig config = configFor(spec);
            // assetManager null: every path in the config is an absolute filesystem path.
            OfflineTts engine = new OfflineTts(null, config);
            return new SherpaTts(engine);
        } catch (AiException e) {
            throw e;
        } catch (Throwable t) {
            throw new AiException(AiException.Kind.LOAD_FAILED, "sherpa-onnx load failed", t);
        }
    }

    private static OfflineTtsConfig configFor(AiTtsSpec spec) throws AiException {
        File root = spec.unpackedRoot;
        OfflineTtsModelConfig model = new OfflineTtsModelConfig();
        model.setNumThreads(NUM_THREADS);
        model.setProvider("cpu");
        model.setDebug(false);

        String engine = spec.engine == null ? "" : spec.engine;
        String dataDir = path(root, "espeak-ng-data");

        if (AiCatalogEntry.TTS_ENGINE_KOKORO.equals(engine)) {
            OfflineTtsKokoroModelConfig k = new OfflineTtsKokoroModelConfig();
            k.setModel(path(root, "model.onnx"));
            k.setVoices(path(root, "voices.bin"));
            k.setTokens(path(root, "tokens.txt"));
            k.setDataDir(dataDir);
            k.setDictDir(optionalDir(root, "dict"));
            k.setLexicon(joinExisting(root, "lexicon-us-en.txt", "lexicon-gb-en.txt", "lexicon-zh.txt"));
            model.setKokoro(k);
        } else if (AiCatalogEntry.TTS_ENGINE_MATCHA.equals(engine)) {
            OfflineTtsMatchaModelConfig m = new OfflineTtsMatchaModelConfig();
            m.setAcousticModel(firstOnnx(root, "model-steps"));
            m.setVocoder(path(root, "vocos-22khz-univ.onnx"));
            m.setTokens(path(root, "tokens.txt"));
            m.setDataDir(dataDir);
            model.setMatcha(m);
        } else {
            // VITS / Piper: one acoustic .onnx, tokens.txt, espeak-ng-data.
            OfflineTtsVitsModelConfig v = new OfflineTtsVitsModelConfig();
            v.setModel(firstOnnx(root, null));
            v.setTokens(path(root, "tokens.txt"));
            v.setDataDir(dataDir);
            model.setVits(v);
        }

        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(model);
        return config;
    }

    @Override
    public AiPcm synthesize(String text, int speakerId, float speed) throws AiException {
        try {
            int sid = Math.max(0, speakerId);
            int speakers = tts.numSpeakers();
            if (speakers > 0 && sid >= speakers) {
                sid = 0;
            }
            float clampedSpeed = speed <= 0f ? 1.0f : speed;
            GeneratedAudio audio = tts.generate(text, sid, clampedSpeed);
            return new AiPcm(audio.getSamples(), audio.getSampleRate());
        } catch (Throwable t) {
            throw new AiException(AiException.Kind.RUNTIME, "synthesis failed", t);
        }
    }

    @Override
    public int numSpeakers() {
        try {
            return tts.numSpeakers();
        } catch (Throwable t) {
            return 1;
        }
    }

    @Override
    public void close() {
        try {
            tts.release();
        } catch (Throwable t) {
            Log.w(TAG, "release failed", t);
        }
    }

    // ---- path helpers ----------------------------------------------------------------------

    private static String path(File root, String name) {
        return new File(root, name).getAbsolutePath();
    }

    private static String optionalDir(File root, String name) {
        File d = new File(root, name);
        return d.isDirectory() ? d.getAbsolutePath() : "";
    }

    /** Comma-joined absolute paths of whichever of {@code names} exist. sherpa accepts an empty string. */
    private static String joinExisting(File root, String... names) {
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            File f = new File(root, name);
            if (f.isFile()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(f.getAbsolutePath());
            }
        }
        return sb.toString();
    }

    /** First {@code *.onnx} under {@code root} (optionally whose name starts with {@code prefix}). */
    private static String firstOnnx(File root, String prefix) throws AiException {
        File[] kids = root.listFiles();
        if (kids != null) {
            for (File f : kids) {
                String n = f.getName();
                if (f.isFile() && n.endsWith(".onnx") && (prefix == null || n.startsWith(prefix))) {
                    return f.getAbsolutePath();
                }
            }
        }
        throw new AiException(AiException.Kind.NOT_INSTALLED,
                "no acoustic .onnx in " + root);
    }

    // ---- archive extraction ----------------------------------------------------------------

    /**
     * Unpacks a {@code .tar.bz2} into {@code destDir}. Guards against path traversal (an entry name
     * escaping {@code destDir}). Called by {@code AiModelDownloadService} via the flavor seam.
     */
    static void extractTarBz2(File archive, File destDir) throws IOException {
        if (!destDir.isDirectory() && !destDir.mkdirs()) {
            throw new IOException("cannot create " + destDir);
        }
        String destCanonical = destDir.getCanonicalPath() + File.separator;
        byte[] buf = new byte[64 * 1024];
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new BZip2CompressorInputStream(
                        new BufferedInputStream(new FileInputStream(archive))))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                File out = new File(destDir, entry.getName());
                if (!out.getCanonicalPath().startsWith(destCanonical)) {
                    throw new IOException("archive entry escapes target: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("cannot create " + parent);
                }
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = tar.read(buf)) != -1) {
                        os.write(buf, 0, n);
                    }
                }
            }
        }
    }
}
