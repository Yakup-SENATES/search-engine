package com.example.searchengine.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import com.example.searchengine.domain.content.ContentType;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapped to the {@code contents} table.
 *
 * <p>This is purely an infrastructure concern — the domain {@code Content} record
 * remains framework-free. Mapping between the two is handled by
 * {@code ContentRepositoryAdapter}.</p>
 *
 * <p>REQ 5.1, 5.2, 5.3, 22.2, 22.3</p>
 */
@Entity
@Table(name = "contents")
public class ContentEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "external_id", nullable = false, length = 128)
    private String externalId;

    @Column(name = "title", nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private ContentType type;

    @Column(name = "views", nullable = false)
    private long views;

    @Column(name = "likes", nullable = false)
    private long likes;

    @Column(name = "reading_time", nullable = false)
    private int readingTime;

    @Column(name = "reactions", nullable = false)
    private long reactions;

    @Column(name = "duration", length = 32)
    private String duration;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", nullable = false, columnDefinition = "TEXT[]")
    private String[] tags;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "final_score", nullable = false)
    private double finalScore;

    @Column(name = "popularity_score", nullable = false)
    private double popularityScore;

    @Column(name = "relevance_score", nullable = false)
    private double relevanceScore;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContentEntity() {
        // Required by JPA
    }

    // --- Getters and Setters ---

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ContentType getType() {
        return type;
    }

    public void setType(ContentType type) {
        this.type = type;
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

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }

    public String[] getTags() {
        return tags;
    }

    public void setTags(String[] tags) {
        this.tags = tags;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public double getFinalScore() {
        return finalScore;
    }

    public void setFinalScore(double finalScore) {
        this.finalScore = finalScore;
    }

    public double getPopularityScore() {
        return popularityScore;
    }

    public void setPopularityScore(double popularityScore) {
        this.popularityScore = popularityScore;
    }

    public double getRelevanceScore() {
        return relevanceScore;
    }

    public void setRelevanceScore(double relevanceScore) {
        this.relevanceScore = relevanceScore;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
