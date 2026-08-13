package de.luhmer.owncloudnewsreader.database.ai;

import de.luhmer.owncloudnewsreader.database.model.RssItem;

/**
 * Derivation of {@code AI_KEY}, the durable identity of an article (PLAN D2).
 *
 * <p>{@code RSS_ITEM_ID} is transient — it is the Nextcloud server id and dies with the article
 * cache. Everything durable (embeddings, decisions, taste, centroids) is keyed on {@code AI_KEY},
 * which also makes the taste model survive Clear-cache for free.</p>
 *
 * <p><b>Why not just the fingerprint.</b>
 * {@code InsertRssItemIntoDatabase.java:75} defaults the fingerprint to {@code ""}, not
 * {@code null}, so the {@code null} guard a few lines further down never fires. A server that omits
 * the field would give every article the empty fingerprint and therefore <b>one AI row for the
 * entire corpus</b>. The empty string is the case that matters; {@code null} is defended against
 * anyway because it is free.</p>
 */
public final class AiKeys {

    /** Prefix for the synthetic fallback key. Deliberately not a legal fingerprint. */
    public static final String ID_PREFIX = "id:";

    private AiKeys() {
    }

    /**
     * @param item a non-null RSS item
     * @return its fingerprint, or {@code "id:<serverId>"} when the fingerprint is null or empty
     */
    public static String of(RssItem item) {
        return of(item.getFingerprint(), item.getId());
    }

    /**
     * Same rule, for callers that already hold the two columns (cursors, raw joins).
     *
     * @param fingerprint {@code RSS_ITEM.FINGERPRINT}, may be null or empty
     * @param rssItemId   {@code RSS_ITEM._id}
     */
    public static String of(String fingerprint, long rssItemId) {
        if (fingerprint == null || fingerprint.isEmpty()) {
            return ID_PREFIX + rssItemId;
        }
        return fingerprint;
    }

    /** @return true when the key is the synthetic per-id fallback rather than a real fingerprint. */
    public static boolean isSynthetic(String aiKey) {
        return aiKey != null && aiKey.startsWith(ID_PREFIX);
    }
}
