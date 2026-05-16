package com.example.searchengine.infrastructure.provider.xmlprovider;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;

import java.util.List;

/**
 * JAXB DTO representing a single {@code <item>} element within the XML feed.
 *
 * <p>Maps to the documented schema:
 * <pre>{@code
 * <item>
 *   <id>ext-456</id>
 *   <headline>Article Title</headline>
 *   <type>article</type>
 *   <stats>...</stats>
 *   <publication_date>2024-01-10T08:00:00Z</publication_date>
 *   <categories>
 *     <category>java</category>
 *   </categories>
 * </item>
 * }</pre>
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class XmlItemDto {

    @XmlElement(name = "id")
    private String id;

    @XmlElement(name = "headline")
    private String headline;

    @XmlElement(name = "type")
    private String type;

    @XmlElement(name = "stats")
    private XmlStatsDto stats;

    @XmlElement(name = "publication_date")
    private String publicationDate;

    @XmlElementWrapper(name = "categories")
    @XmlElement(name = "category")
    private List<String> categories;

    public XmlItemDto() {
        // JAXB requires a no-arg constructor
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHeadline() {
        return headline;
    }

    public void setHeadline(String headline) {
        this.headline = headline;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public XmlStatsDto getStats() {
        return stats;
    }

    public void setStats(XmlStatsDto stats) {
        this.stats = stats;
    }

    public String getPublicationDate() {
        return publicationDate;
    }

    public void setPublicationDate(String publicationDate) {
        this.publicationDate = publicationDate;
    }

    public List<String> getCategories() {
        return categories;
    }

    public void setCategories(List<String> categories) {
        this.categories = categories;
    }
}
