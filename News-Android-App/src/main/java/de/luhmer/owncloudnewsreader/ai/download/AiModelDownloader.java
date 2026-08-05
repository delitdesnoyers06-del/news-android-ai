package de.luhmer.owncloudnewsreader.ai.download;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

import de.luhmer.owncloudnewsreader.ai.AiCapability;
import de.luhmer.owncloudnewsreader.ai.engine.CancelToken;
import de.luhmer.owncloudnewsreader.ai.model.AiCatalogEntry;
import de.luhmer.owncloudnewsreader.ai.model.AiPartMeta;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The transfer itself: resumable, verified, and free of Android and of the Service that hosts it.
 *
 * <h3>Resume (PLAN D23)</h3>
 * <ul>
 *   <li><b>Only the origin {@code resolve/main/...} URL is persisted, and it is re-resolved on every
 *       attempt.</b> Hugging Face 302s to a CDN URL signed with {@code Expires=} ~15 minutes;
 *       persisting the redirect target turns "resume after lunch" into a CloudFront 403 that the UI
 *       would report as a corrupt download.</li>
 *   <li><b>No {@code If-Range}.</b> The CDN's {@code etag} is the xet content hash and differs from
 *       the origin's {@code x-linked-etag} (which is the SHA-256), so the validator would never
 *       match. The resume is validated with {@code x-linked-size} plus the final digest instead.</li>
 *   <li><b>A 200 where a 206 was asked for means the server ignored the Range.</b> The bytes already
 *       on disk are then a prefix of nothing in particular: discard the {@code .part} and restart.</li>
 * </ul>
 *
 * <p>Nothing here throws to the caller: every outcome is an {@link Outcome} with a machine-readable
 * {@link Outcome#code}.</p>
 */
public final class AiModelDownloader {

    private static final String TAG = "AiModelDownloader";

    private static final int BUFFER = 256 * 1024;
    private static final long META_EVERY_BYTES = 4L * 1024 * 1024;
    private static final long LOW_DISK_ABORT_BYTES = 64L * 1024 * 1024;

    public static final String CODE_OK = "ok";
    public static final String CODE_GATED = "gated";
    public static final String CODE_NETWORK = "network";
    public static final String CODE_NO_SPACE = "no_space";
    public static final String CODE_CHECKSUM = "checksum";
    public static final String CODE_SIZE_MISMATCH = "size_mismatch";
    public static final String CODE_CANCELLED = "cancelled";
    public static final String CODE_IO = "io";

    /** Called from the transfer thread, roughly per 256 KB. Must be cheap. */
    public interface Progress {
        void onProgress(String modelId, long received, long total);
    }

    public static final class Outcome {
        public final String code;
        public final String message;
        public final long received;
        /** HTTP status of the last response, or 0. Only meaningful for the gated/network codes. */
        public final int httpStatus;

        Outcome(String code, String message, long received, int httpStatus) {
            this.code = code;
            this.message = message;
            this.received = received;
            this.httpStatus = httpStatus;
        }

        public boolean ok() {
            return CODE_OK.equals(code);
        }
    }

    private final OkHttpClient client;

    public AiModelDownloader() {
        this(defaultClient());
    }

    public AiModelDownloader(OkHttpClient client) {
        this.client = client;
    }

    private static OkHttpClient defaultClient() {
        return new OkHttpClient.Builder()
                // No call timeout: a 2.6 GB transfer is legitimately long. The socket-level read
                // timeout is what detects a dead connection.
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Downloads (or resumes) {@code entry} into {@code repo}'s layout and, on success, atomically
     * renames the verified {@code .part} into place.
     *
     * @param hfToken the Hugging Face token, or {@code null}. Mandatory for {@code entry.gated}:
     *                an anonymous request to a gated repo answers <b>401</b>, and accepting the
     *                licence in a browser does not change that (PLAN D22).
     */
    public Outcome download(AiModelRepository repo, AiCatalogEntry entry, String hfToken,
                            CancelToken token, Progress progress) {
        File dir = repo.dirFor(entry);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return new Outcome(CODE_IO, "cannot create " + dir, 0L, 0);
        }
        File part = repo.partFor(entry);
        File sidecar = repo.metaFor(entry);
        File finalFile = repo.fileFor(entry);

        AiPartMeta meta = AiPartMeta.read(sidecar);
        if (meta != null && !meta.matches(entry)) {
            // The upstream file changed: the bytes we hold are a prefix of a different file.
            Log.i(TAG, "part metadata no longer matches the catalogue; restarting " + entry.id);
            deleteQuietly(part);
            meta = null;
        }
        if (meta == null) {
            meta = new AiPartMeta();
            meta.modelId = entry.id;
            meta.url = entry.downloadUrl();
            meta.size = entry.sizeBytes;
            meta.sha256 = entry.sha256;
            meta.md5 = entry.md5;
            meta.startedAt = System.currentTimeMillis();
        }
        long received = part.isFile() ? part.length() : 0L;
        if (received > entry.sizeBytes) {
            deleteQuietly(part);
            received = 0L;
        }
        meta.received = received;
        meta.write(sidecar);

        Outcome transfer = transfer(entry, part, sidecar, meta, received, hfToken, token, progress);
        if (!transfer.ok()) {
            return transfer;
        }
        if (part.length() != entry.sizeBytes) {
            return new Outcome(CODE_SIZE_MISMATCH,
                    part.length() + " bytes, expected " + entry.sizeBytes, part.length(), 0);
        }
        String verify = verify(part, entry);
        if (verify != null) {
            deleteQuietly(part);
            deleteQuietly(sidecar);
            return new Outcome(CODE_CHECKSUM, verify, entry.sizeBytes, 0);
        }
        deleteQuietly(finalFile);
        if (!part.renameTo(finalFile)) {
            return new Outcome(CODE_IO, "rename failed", entry.sizeBytes, 0);
        }
        deleteQuietly(sidecar);
        return new Outcome(CODE_OK, null, entry.sizeBytes, 200);
    }

    /**
     * Downloads a single plain file (a TTS companion such as a Matcha vocoder) to {@code dest}. No
     * resume and no sidecar — companions are small next to the archive they accompany. Verified by
     * size only when {@code expectedSize > 0}.
     */
    public Outcome downloadCompanion(String url, File dest, long expectedSize,
                                     CancelToken token, Progress progress) {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return new Outcome(CODE_IO, "cannot create " + parent, 0L, 0);
        }
        Request req = new Request.Builder().url(url).build();
        try (Response r = client.newCall(req).execute()) {
            if (!r.isSuccessful()) {
                return new Outcome(CODE_NETWORK, "HTTP " + r.code(), 0L, r.code());
            }
            ResponseBody body = r.body();
            if (body == null) {
                return new Outcome(CODE_NETWORK, "empty body", 0L, r.code());
            }
            long received = 0L;
            try (InputStream in = body.byteStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[BUFFER];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    received += n;
                    if (progress != null) {
                        progress.onProgress(dest.getName(), received, expectedSize);
                    }
                    if (token != null && token.isCancelled()) {
                        deleteQuietly(dest);
                        return new Outcome(CODE_CANCELLED, token.reason(), received, 0);
                    }
                }
                out.flush();
            }
            if (expectedSize > 0 && dest.length() != expectedSize) {
                deleteQuietly(dest);
                return new Outcome(CODE_SIZE_MISMATCH,
                        dest.length() + " bytes, expected " + expectedSize, dest.length(), 0);
            }
            return new Outcome(CODE_OK, null, dest.length(), 200);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(dest);
            return new Outcome(CODE_NETWORK, String.valueOf(e.getMessage()), 0L, 0);
        }
    }

    private Outcome transfer(AiCatalogEntry entry, File part, File sidecar, AiPartMeta meta,
                             long startAt, String hfToken, CancelToken token, Progress progress) {
        long received = startAt;
        boolean restarted = false;
        while (true) {
            Request.Builder rb = new Request.Builder().url(entry.downloadUrl());
            if (received > 0) {
                rb.header("Range", "bytes=" + received + "-");
            }
            if (entry.gated && hfToken != null && !hfToken.isEmpty()) {
                rb.header("Authorization", "Bearer " + hfToken);
            }
            try (Response r = client.newCall(rb.build()).execute()) {
                if (r.code() == 401 || r.code() == 403) {
                    return new Outcome(CODE_GATED, header(r, "x-error-code"), received, r.code());
                }
                if (!r.isSuccessful()) {
                    return new Outcome(CODE_NETWORK, "HTTP " + r.code(), received, r.code());
                }
                String linked = header(r, "x-linked-size");
                if (linked != null && !String.valueOf(entry.sizeBytes).equals(linked.trim())) {
                    return new Outcome(CODE_SIZE_MISMATCH,
                            "server says " + linked + ", catalogue says " + entry.sizeBytes,
                            received, r.code());
                }
                if (received > 0 && r.code() != 206) {
                    // Range ignored. Everything on disk is now untrustworthy.
                    if (restarted) {
                        return new Outcome(CODE_NETWORK, "server refuses ranges", 0L, r.code());
                    }
                    Log.i(TAG, "no 206 for " + entry.id + "; discarding the partial file");
                    deleteQuietly(part);
                    received = 0L;
                    restarted = true;
                    continue;
                }
                ResponseBody body = r.body();
                if (body == null) {
                    return new Outcome(CODE_NETWORK, "empty body", received, r.code());
                }
                return copy(entry, part, sidecar, meta, received, body, token, progress);
            } catch (IOException e) {
                return new Outcome(CODE_NETWORK, String.valueOf(e.getMessage()), received, 0);
            } catch (RuntimeException e) {
                return new Outcome(CODE_NETWORK, String.valueOf(e.getMessage()), received, 0);
            }
        }
    }

    private Outcome copy(AiCatalogEntry entry, File part, File sidecar, AiPartMeta meta,
                         long startAt, ResponseBody body, CancelToken token, Progress progress) {
        long received = startAt;
        long lastMeta = received;
        try (InputStream in = body.byteStream();
             FileOutputStream out = new FileOutputStream(part, received > 0)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                received += n;
                if (progress != null) {
                    progress.onProgress(entry.id, received, entry.sizeBytes);
                }
                if (received - lastMeta >= META_EVERY_BYTES) {
                    lastMeta = received;
                    meta.received = received;
                    meta.write(sidecar);
                    if (AiCapability.freeBytes(part.getParentFile()) < LOW_DISK_ABORT_BYTES) {
                        return new Outcome(CODE_NO_SPACE, "less than 64 MB left", received, 0);
                    }
                }
                if (token != null && token.isCancelled()) {
                    meta.received = received;
                    meta.write(sidecar);
                    return new Outcome(CODE_CANCELLED, token.reason(), received, 0);
                }
            }
            out.flush();
        } catch (IOException | RuntimeException e) {
            meta.received = part.length();
            meta.write(sidecar);
            return new Outcome(CODE_NETWORK, String.valueOf(e.getMessage()), part.length(), 0);
        }
        meta.received = received;
        meta.write(sidecar);
        return new Outcome(CODE_OK, null, received, 200);
    }

    /**
     * @return null when the file is what the catalogue says it is, else a human-readable reason.
     *         An entry with no checksum at all (a gated repo masks its LFS oid) is accepted on size
     *         alone — stated here rather than pretended away.
     */
    public static String verify(File file, AiCatalogEntry entry) {
        if (entry.sha256 != null && !entry.sha256.isEmpty()) {
            String actual = digest(file, "SHA-256");
            if (actual == null) {
                return "could not hash the file";
            }
            return actual.equalsIgnoreCase(entry.sha256) ? null
                    : "sha256 " + actual + " != " + entry.sha256;
        }
        if (entry.md5 != null && !entry.md5.isEmpty()) {
            String actual = digest(file, "MD5");
            if (actual == null) {
                return "could not hash the file";
            }
            return actual.equalsIgnoreCase(entry.md5) ? null : "md5 " + actual + " != " + entry.md5;
        }
        return null;
    }

    public static String digest(File file, String algorithm) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return hex(md.digest());
        } catch (Throwable t) {
            Log.w(TAG, "digest failed", t);
            return null;
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static String header(Response r, String name) {
        return r.header(name);
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
