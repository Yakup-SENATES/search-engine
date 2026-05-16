# PROJECT SPECIFICATION: Search Engine Aggregation Service (Java/Spring Boot)

## 1. Project Overview
[cite_start]Bu proje, farklı içerik sağlayıcılardan (JSON ve XML) gelen verileri tek bir merkezde toplayan, normalize eden ve özel bir puanlama algoritmasıyla sıralayan ölçeklenebilir bir API servisidir[cite: 17, 41]. 

## 2. Technical Stack
* **Language:** Java 21 (LTS)
* **Framework:** Spring Boot 3.4+
* [cite_start]**Database:** PostgreSQL (Persistence & Data Integrity) [cite: 45, 47]
* [cite_start]**Caching:** Spring Cache (Redis recommendation) [cite: 48]
* [cite_start]**API Documentation:** SpringDoc OpenAPI (Swagger) [cite: 58]
* **Architecture:** Clean Architecture / Hexagonal Architecture

## 3. Core Requirements & Logic

### 3.1 Content Providers
[cite_start]Sistem, yeni sağlayıcılar eklemeye uygun (Strategy Pattern) bir yapıda olmalıdır[cite: 44].
* [cite_start]**Provider 1 (JSON):** JSON formatında veri çeker[cite: 67].
* [cite_start]**Provider 2 (XML):** XML formatında veri çeker[cite: 68].

### 3.2 Scoring Engine (The Formula)
[cite_start]Tüm içerikler aşağıdaki formüle göre puanlanmalıdır[cite: 70]:

$$FinalScore = (BaseScore \times TypeMultiplier) + FreshnessScore + EngagementScore$$

**Base Score Calculation:**
* [cite_start]**Video:** $\frac{views}{1000} + \frac{likes}{100}$ [cite: 72]
* [cite_start]**Text:** $reading\_time + \frac{reactions}{50}$ [cite: 73]

**Multipliers & Bonus Points:**
* [cite_start]**Type Multiplier:** Video: 1.5, Text: 1.0 [cite: 75, 76]
* **Engagement Score:**
    * [cite_start]Video: $(\frac{likes}{views}) \times 10$ [cite: 84]
    * [cite_start]Text: $(\frac{reactions}{reading\_time}) \times 5$ [cite: 86]
* **Freshness Score (Zaman Bazlı):**
    * [cite_start]< 1 hafta: +5 puan [cite: 78]
    * [cite_start]< 1 ay: +3 puan [cite: 80]
    * [cite_start]< 3 ay: +1 puan [cite: 81]
    * [cite_start]Diğer: +0 [cite: 82]

### 3.3 API Endpoints
* [cite_start]`GET /api/v1/search`: Anahtar kelime arama, içerik türü filtreleme (video/text), skora göre sıralama ve pagination desteği sunmalıdır[cite: 23, 24, 25, 27].
* [cite_start]`GET /dashboard`: Başlık, içerik türü ve skora göre sıralanmış listeleme arayüzü (Thymeleaf önerilir)[cite: 33, 39].

## 4. Development Epics

### Epic 1: Foundation
* Spring Boot iskeletinin kurulması ve Docker Compose (Postgres) yapılandırması.
* Domain modellerinin (Content, ProviderType) oluşturulması.

### Epic 2: Data Ingestion & Strategy
* `ContentProvider` interface tanımı.
* JSON ve XML sağlayıcılar için adaptörlerin yazılması.
* Veri normalizasyonu ve veritabanına kaydetme (Upsert mantığı).

### Epic 3: Scoring & Search
* Scoring Engine implementasyonu.
* Search API'nin filtreleme ve pagination ile geliştirilmesi.

### Epic 4: Middleware & Optimization (Final)
* [cite_start]**Rate Limiting:** API uç noktalarına istek limiti yönetimi eklenmesi[cite: 42].
* **Caching:** Sık sorgulanan sonuçların cache'lenmesi.

## 5. Non-Functional Requirements
* [cite_start]Temiz ve anlaşılır kod yapısı (SOLID)[cite: 51].
* [cite_start]Mantıklı hata yönetimi (Global Exception Handler)[cite: 53].
* [cite_start]Birim testleri (JUnit/Mockito)[cite: 54].