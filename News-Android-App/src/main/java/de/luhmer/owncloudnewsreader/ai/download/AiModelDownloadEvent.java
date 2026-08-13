package de.luhmer.owncloudnewsreader.ai.download;

/**
 * Download progress and completion, posted on EventBus — the mechanism this app already uses for
 * exactly this shape ({@code PodcastDownloadService.DownloadProgressUpdate}, subscribed from a
 * RecyclerView holder's attach/detach). No polling, no LiveData, no new dependency.
 */
public class AiModelDownloadEvent {

    public enum Phase {
        STARTED,
        PROGRESS,
        /** Bytes are on disk; the SHA-256 pass is running. */
        VERIFYING,
        /** The model is being opened once to prove it works. */
        PREPARING,
        INSTALLED,
        /** Paused or aborted; {@link #code} says which. Never a crash. */
        FAILED,
        CANCELLED
    }

    public final String modelId;
    public final Phase phase;
    public final long received;
    public final long total;
    /** One of the {@code AiModelDownloader.CODE_*} constants, or null. */
    public final String code;
    public final String message;

    public AiModelDownloadEvent(String modelId, Phase phase, long received, long total,
                                String code, String message) {
        this.modelId = modelId;
        this.phase = phase;
        this.received = received;
        this.total = total;
        this.code = code;
        this.message = message;
    }

    public static AiModelDownloadEvent progress(String modelId, long received, long total) {
        return new AiModelDownloadEvent(modelId, Phase.PROGRESS, received, total, null, null);
    }

    public int percent() {
        return total <= 0 ? 0 : (int) Math.min(100L, received * 100L / total);
    }
}
