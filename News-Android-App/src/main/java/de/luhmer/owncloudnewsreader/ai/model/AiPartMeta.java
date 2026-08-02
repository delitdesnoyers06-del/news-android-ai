package de.luhmer.owncloudnewsreader.ai.model;

import android.util.Log;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * The sidecar written next to a {@code .part} file: what we were downloading, from where, and how
 * far we got.
 *
 * <p>{@link #url} is always the <b>origin</b> URL ({@code resolve/main/...}), never the CDN
 * redirect target — PLAN D23. The redirect is signed with {@code Expires=} ~15 minutes, so
 * persisting it turns "resume after lunch" into a CloudFront 403 that surfaces as a corrupt
 * download.</p>
 *
 * <p>Every read is best-effort: a missing or unparseable sidecar means "start over", which is
 * always safe. It never throws.</p>
 */
public class AiPartMeta {

    private static final String TAG = "AiPartMeta";

    public String modelId;
    /** The origin URL. Re-resolved on every attempt. */
    public String url;
    public long size;
    public long received;
    public String sha256;
    public String md5;
    public long startedAt;
    public long updatedAt;

    public static File sidecarFor(File partFile) {
        String p = partFile.getAbsolutePath();
        if (p.endsWith(".part")) {
            p = p.substring(0, p.length() - ".part".length());
        }
        return new File(p + ".meta");
    }

    /** @return the parsed sidecar, or {@code null} when there is none or it is unusable */
    public static AiPartMeta read(File sidecar) {
        if (sidecar == null || !sidecar.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(sidecar)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            AiPartMeta m = new Gson().fromJson(
                    new String(bos.toByteArray(), Charset.forName("UTF-8")), AiPartMeta.class);
            return m != null && m.modelId != null ? m : null;
        } catch (Throwable t) {
            Log.w(TAG, "unreadable part metadata at " + sidecar + "; will restart", t);
            return null;
        }
    }

    /** @return true when the sidecar was written; false is not fatal, it only costs a resume */
    public boolean write(File sidecar) {
        updatedAt = System.currentTimeMillis();
        try (FileOutputStream out = new FileOutputStream(sidecar)) {
            out.write(new Gson().toJson(this).getBytes(Charset.forName("UTF-8")));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "could not persist part metadata to " + sidecar, t);
            return false;
        }
    }

    /**
     * True when this sidecar describes the same download as {@code entry}. A size or checksum change
     * means the file changed upstream, so the partial bytes are garbage and must be discarded.
     */
    public boolean matches(AiCatalogEntry entry) {
        if (entry == null || !entry.id.equals(modelId)) {
            return false;
        }
        if (size != entry.sizeBytes) {
            return false;
        }
        return eq(sha256, entry.sha256) && eq(md5, entry.md5);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
