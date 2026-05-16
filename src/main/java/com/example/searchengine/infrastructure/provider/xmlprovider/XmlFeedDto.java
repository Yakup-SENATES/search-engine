package com.example.searchengine.infrastructure.provider.xmlprovider;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.util.List;

/**
 * JAXB DTO representing the root {@code <feed>} element of the XML provider response.
 *
 * <p>Schema:
 * <pre>{@code
 * <feed>
 *   <items>
 *     <item>...</item>
 *   </items>
 * </feed>
 * }</pre>
 *
 * <p>REQ 3.1</p>
 */
@XmlRootElement(name = "feed")
@XmlAccessorType(XmlAccessType.FIELD)
public class XmlFeedDto {

    @XmlElementWrapper(name = "items")
    @XmlElement(name = "item")
    private List<XmlItemDto> items;

    public XmlFeedDto() {
        // JAXB requires a no-arg constructor
    }

    public List<XmlItemDto> getItems() {
        return items;
    }

    public void setItems(List<XmlItemDto> items) {
        this.items = items;
    }
}
