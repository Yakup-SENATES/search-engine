package com.example.searchengine.infrastructure.provider.xmlprovider;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;

/**
 * JAXB DTO representing the {@code <stats>} element within an XML feed item.
 *
 * <p>All fields default to 0 when absent (REQ 3.5).</p>
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class XmlStatsDto {

    @XmlElement(name = "views")
    private long views;

    @XmlElement(name = "likes")
    private long likes;

    @XmlElement(name = "reading_time")
    private int readingTime;

    @XmlElement(name = "reactions")
    private long reactions;

    public XmlStatsDto() {
        // JAXB requires a no-arg constructor
    }

    public long getViews() {
        return views;
    }

    public void setViews(long views) {
        this.views = views;
    }

    public long getLikes() {
        return likes;
    }

    public void setLikes(long likes) {
        this.likes = likes;
    }

    public int getReadingTime() {
        return readingTime;
    }

    public void setReadingTime(int readingTime) {
        this.readingTime = readingTime;
    }

    public long getReactions() {
        return reactions;
    }

    public void setReactions(long reactions) {
        this.reactions = reactions;
    }
}
