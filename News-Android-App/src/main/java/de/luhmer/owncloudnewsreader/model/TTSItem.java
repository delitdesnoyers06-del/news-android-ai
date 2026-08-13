package de.luhmer.owncloudnewsreader.model;

public class TTSItem extends MediaItem {

    public TTSItem(long itemId, String author, String title, String text, String favIcon) {
        this.itemId = itemId;
        this.author = author;
        this.title = title;
        this.text = text;
        this.favIcon = favIcon;
    }

    public String text;

    /**
     * BCP-47 base language (e.g. {@code "en"}, {@code "fr"}) chosen by the user for this article,
     * or {@code null} to detect it automatically. Overrides {@link de.luhmer.owncloudnewsreader
     * .services.podcast.ArticleLanguage} auto-detection when set.
     */
    public String ttsLanguage;
}
