# Search Engine Aggregator — Teknik Tur (TR)

Bu yazı, projeye ilk defa bakan bir developer için "uygulamanın hayat döngüsünde veri nereden geliyor, ne oluyor, nereye yazılıyor, nasıl sorgulanıyor" sorularına uçtan uca yanıt veriyor. Yapıyı kendi başına gezebilmen için her adımda hangi sınıfların devreye girdiğini de işaretledim.

> İngilizce operator/setup rehberi için ana dizindeki [`README.md`](../README.md) dosyasına bak. Bu dosya tamamlayıcı — kurulumdan çok mimariyi ve veri akışını anlatıyor.

## İçindekiler

- [30 saniyelik özet](#30-saniyelik-özet)
- [Mimari katmanlar](#mimari-katmanlar)
- [Veri akışı — uçtan uca](#veri-akışı--uçtan-uca)
- [1) Veri nereden geliyor — Provider katmanı](#1-veri-nereden-geliyor--provider-katmanı)
- [2) Veri nasıl işleniyor — Aggregator pipeline](#2-veri-nasıl-işleniyor--aggregator-pipeline)
- [3) Veri nereye kaydediliyor — PostgreSQL](#3-veri-nereye-kaydediliyor--postgresql)
- [4) Veri nasıl sorgulanıyor — Search API](#4-veri-nasıl-sorgulanıyor--search-api)
- [5) Cross-cutting concerns](#5-cross-cutting-concerns)
- [6) İşlevsel Olmayan Gözlemlenebilirlik](#6-i̇şlevsel-olmayan-gözlemlenebilirlik)
- [7) Konfigürasyon](#7-konfigürasyon)
- [8) Test stratejisi](#8-test-stratejisi)
- [9) Build ve çalıştırma](#9-build-ve-çalıştırma)
- [Kafanda canlandırması için tipik bir istek](#kafanda-canlandırması-için-tipik-bir-istek)
- [Kod gezerken takılacağın yerler](#kod-gezerken-takılacağın-yerler)

## 30 saniyelik özet

İki dış kaynaktan (biri JSON, biri XML) periyodik olarak içerik çekiyoruz, normalize edip ortak bir `Content` modeline dönüştürüyoruz, deterministik bir skor hesaplıyoruz, PostgreSQL'e yazıyoruz, ardından bu verileri tam metin araması ve sıralamayla birlikte HTTP üzerinden sunuyoruz. Spring Boot 3.4 + Java 21, Clean Architecture, Strategy Pattern ile providerlar — özet böyle.

## Mimari katmanlar

`com.example.searchengine` altında dört katman var, içe doğru bağımlılık kuralı ArchUnit testleriyle build zamanında zorlanıyor (`src/test/java/.../architecture/`):

```
domain          → Çerçeve (framework) bağımlılığı YOK. Saf Java.
application     → Use-case'ler. domain'e bağımlı, başka kimseye değil.
infrastructure  → Spring/JPA/JAXB/Jackson adapter'ları. application + domain'i içe alır.
web             → Controller'lar ve DTO'lar. infrastructure'ı GÖREMEZ.
```

Bu kuralı kafanda tutmak çok şeyi açıklıyor: skorlama mantığı `domain.scoring`'de saf Java duruyor çünkü test edilmesi ve doğruluğunun korunması framework'ten bağımsız olmalı; controller'lar JPA repository'sine doğrudan ulaşamaz, hep application servisine konuşur.

## Veri akışı — uçtan uca

```
[Provider 1 JSON]       [Provider 2 XML]
       │                       │
       ▼                       ▼
JsonProviderAdapter   XmlProviderAdapter        (infrastructure/provider/*)
       │                       │
       └───────────┬───────────┘
                   ▼
            ContentAggregator                   (application/ingest/)
                   │
       ┌───────────┼───────────┐
       ▼           ▼           ▼
   Normalizer  ScoringEngine   ContentRepository
   (uygula.    (domain/        (port: domain;
    ingest)    scoring)         impl: infra/persistence)
                                       │
                                       ▼
                                   PostgreSQL
                                   (contents tablosu,
                                    GIN tsvector index)

       [HTTP istemcisi]
              │
              ▼
       SearchController              (web/api/)
              │
              ▼
        SearchService                (application/search/)
              │
              ▼
       Redis search cache → cache miss ise → ContentRepository → PostgreSQL FTS
```

## 1) Veri nereden geliyor — Provider katmanı

İki concrete provider var, ikisi de `domain.provider.ContentProvider` arayüzünü implement ediyor:

```java
public interface ContentProvider {
    String name();              // örn. "provider1-json"
    List<RawContent> fetch();   // ağ hatasında istisna fırlatmaz, [] döner
}
```

**JSON provider** (`infrastructure/provider/jsonprovider/`):

- `JsonProviderClient` — 5 sn connect / 10 sn read timeout'lu `RestClient` ile GET çağırıyor. Resilience4j Retry ile 3 denemeye kadar exponential backoff (500 ms × 2). Body'yi `String` olarak alıp Jackson `ObjectMapper` ile manuel parse ediyor — bunu özellikle yaptık çünkü GitHub raw gibi kaynaklar `Content-Type: text/plain` döndürüyor; default Jackson converter'ı sadece `application/json`'ı kabul ediyor.
- `JsonProviderResponse / JsonContentDto / JsonMetrics` — Jackson record'ları, mock şemasıyla birebir: `{ contents: [{ id, title, type, metrics: { views, likes, duration }, published_at, tags }] }`.
- `JsonContentMapper` — DTO'yu `RawContent`'e çeviriyor.
- `JsonProviderAdapter` — `@Component`, `ContentProvider` implementasyonu. Transport hatasında `[]` döner, tek tek bozuk item'ları logladıktan sonra atlar (per-item failure isolation).

**XML provider** (`infrastructure/provider/xmlprovider/`):

- `XmlProviderClient` — aynı RestClient yapısı, body'yi `String` alıyor, JAXB ile parse ediyor. Hatalı XML → `Optional.empty()`.
- `XmlFeedDto / XmlItemDto / XmlStatsDto` — JAXB anotasyonlu DTO'lar, mock şemasını yansıtıyor: `<feed><items><item><id><headline><type><stats>…<publication_date><categories>`.
- `XmlContentMapper` — alan eşleştirme yapıyor. Önemli detay: provider'ın `type=article` değerini bizim `ContentType.TEXT`'e çeviriyor (`ContentType.fromProviderValue`'da). `publication_date` alanını dört formatta deniyor sırayla: tam `Instant`, `OffsetDateTime`, `LocalDateTime`, sade `LocalDate` (sade tarih için UTC midnight). Bu esneklik, mock'taki `2024-03-15` gibi saatsiz tarihler için gerekli.

`HttpClientConfig` (`infrastructure/provider/`) iki adet adlı `RestClient` bean'i (`jsonRestClient`, `xmlRestClient`) ve provider başına Resilience4j `Retry` + `TimeLimiter` (10 sn overall) konfigüre ediyor.

Yeni bir provider eklemek istiyorsan: `infrastructure/provider/<isim>/` altına `client + DTO + mapper + adapter` dörtlüsünü koy, adapter'ı `@Component` yap. Aggregator hiçbir değişiklik istemiyor çünkü `List<ContentProvider>`'ı inject ediyor — Spring tüm beanleri otomatik enjekte eder. Bu Strategy Pattern.

## 2) Veri nasıl işleniyor — Aggregator pipeline

Her şey `DefaultContentAggregator.runSync()` içinde dönüyor (`application/ingest/`):

```
for each provider in providers:
    try:
        rawItems = provider.fetch()   ← provider hata verirse loglayıp diğerine geç
    catch:
        continue

    for each raw in rawItems:
        result = normalizer.normalize(provider.name(), raw)
        if result is Rejected: log & continue
        content = result.content
        breakdown = scoringEngine.score(content, clock.instant())
        scored = content with breakdown.finalScore + popularityScore
        try:
            repository.upsert(scored)   ← DB hatası olursa loglayıp item'ı atla
        catch:
            continue
```

`@CacheEvict(cacheNames="search", allEntries=true)` annotation'ı `runSync` üzerinde — başarılı bir senkronizasyon sonunda Redis'teki `search` cache region'ı tamamen siliniyor. Bu sayede arama sonuçları en geç 5 dk geride kalmış oluyor.

**`DefaultNormalizer`** (`application/ingest/`):

- Her `RawContent`'i alıp önce sanitize ediyor (Cc kategorisindeki kontrol karakterleri çıkarılıyor, `\t \n \r` korunuyor — XSS / log injection güvenliği için).
- Validation: boş `title` / `externalId` / `publishedAt`, geçersiz `type`, negatif metrikler — tümü `Rejected(field, reason)` dönüyor (exception fırlatmıyor; bu sayede tek bir bozuk item batch'i durdurmuyor).
- Geçen item'lar `NormalizationResult.Accepted(Content)` olarak dönüyor.
- Hata durumlarında WARN seviyesinde provider adı + externalId + alan adı loglanıyor.

**`DefaultScoringEngine`** (`domain/scoring/`):

Saf Java. Hiçbir Spring/JPA/Jackson import'u yok — ArchUnit bunu zorluyor. Formül:

```
finalScore = (baseScore × typeMultiplier) + freshnessScore + engagementScore
```

Dört alt hesaplayıcı var, her biri tek görevli pure function:

| Bileşen | Video | Text |
| --- | --- | --- |
| `BaseScoreCalculator` | `views/1000 + likes/100` | `readingTime + reactions/50` |
| `TypeMultiplier` | 1.5 | 1.0 |
| `EngagementScoreCalculator` | `(likes/views) × 10`, views=0 ise 0 | `(reactions/readingTime) × 5`, readingTime=0 ise 0 |
| `FreshnessScoreCalculator` | ≤7 gün:5, ≤30:3, ≤90:1, fazlası:0 | aynı |

Determinism kritik: aynı `(content, instant)` çifti her zaman aynı sayıyı üretmeli. Bu yüzden `evaluationAt` parametre olarak geçiyor — `Instant.now()` çağrısı yok. Test edilebilirlik ve property-based test ile kanıtlanıyor (`ScoringEnginePropertyTest`, 200 iterasyon).

## 3) Veri nereye kaydediliyor — PostgreSQL

**Schema** Flyway migration ile yönetiliyor — `src/main/resources/db/migration/V1__init.sql`. Tek baseline migration var, gelecekteki değişiklikler için `V2__…sql`, `V3__…sql` şeklinde yeni dosya açılır; **`V1`'e dokunulmaz**.

`contents` tablosunun anahtar özellikleri:

```sql
id              UUID PRIMARY KEY
provider        VARCHAR NOT NULL
external_id     VARCHAR NOT NULL
title, description, type, views, likes, reading_time, reactions,
duration, tags TEXT[], published_at TIMESTAMPTZ,
final_score, popularity_score, relevance_score DOUBLE PRECISION,
created_at, updated_at TIMESTAMPTZ,

UNIQUE (provider, external_id)              -- duplicate önleme
INDEX  (type)                                -- type filtresi
INDEX  (final_score DESC)                    -- sıralama
GIN    (to_tsvector('simple', title || ' ' || coalesce(description, '')))
                                             -- tam metin arama
```

`spring.jpa.hibernate.ddl-auto=validate` — Hibernate hiçbir zaman otomatik migration yapmaz, sadece schema'yı doğrular. Schema sahipliği tamamen Flyway'de.

**Repository çift katmanlı** (port + adapter, hexagonal pattern):

- `domain.content.ContentRepository` — port, saf interface. Domain'in dış dünyaya bakan kapısı.
- `infrastructure.persistence.ContentRepositoryAdapter` — bu portu implement ediyor. `ContentEntity`'ye `Content` ↔ entity mapping yapıyor; `DataAccessException`'ı domain'in `ContentRepositoryException`'ına çeviriyor.
- `infrastructure.persistence.ContentJpaRepository` — Spring Data JPA repository, `ContentEntity` üzerinde çalışıyor. İki kritik native query var:

**Upsert** — atomik, `INSERT … ON CONFLICT (provider, external_id) DO UPDATE … RETURNING (xmax = 0) AS inserted`. PostgreSQL'in `xmax = 0` özelliği insert'ü update'ten ayırt etmemizi sağlıyor; `UpsertOutcome.INSERTED` veya `UPDATED` dönüyoruz. Bu tek SQL ifadesi sayesinde aynı `(provider, externalId)` çiftiyle gelen item önce sorgulanıp sonra update edilmiyor — race condition yok.

**Search** — `plainto_tsquery('simple', :q)` kullanıyor (kullanıcı input'unu otomatik escape eder, SQL injection riski yok). Sıralama parametrik bir `CASE` ile yapılıyor; `id ASC` deterministic tie-break veriyor:

```sql
ORDER BY
  CASE WHEN :sort = 'SCORE'      THEN final_score      END DESC,
  CASE WHEN :sort = 'POPULARITY' THEN popularity_score END DESC,
  CASE WHEN :sort = 'RELEVANCE'
       THEN ts_rank_cd(to_tsvector(...), plainto_tsquery(...))
  END DESC,
  id ASC
OFFSET :offset LIMIT :limit
```

Önemli mimari detay: `ContentEntity` ile `Content` ayrı sınıflar. `Content` domain record'u, JPA bilgisi yok; `ContentEntity` JPA-anotasyonlu, sadece infrastructure katmanında yaşıyor. Mapping `ContentRepositoryAdapter`'da yapılıyor. Bu sayede domain framework-bağımsız kalıyor.

## 4) Veri nasıl sorgulanıyor — Search API

`SearchController` (`web/api/`):

```
GET /api/v1/search?q=…&type=video|text&sort=score|popularity|relevance&page=1&limit=10
```

**Validation** Jakarta Bean Validation ile DTO seviyesinde:

- `q`: `@NotBlank @Size(min=1, max=200)`
- `type`: `@Pattern("^(video|text)$")`
- `sort`: `@Pattern("^(score|popularity|relevance)$")`
- `page`: `@Min(1)`
- `limit`: `@Min(1) @Max(100)`

Bunlar fail ederse `MethodArgumentNotValidException` fırlıyor; `GlobalExceptionHandler` (`web/error/`) bunu yakalayıp standart envelope'a çeviriyor:

```json
{ "error": { "code": "INVALID_QUERY", "message": "q must not be blank" } }
```

Tüm hata sınıfları için bu tablodaki haritalama uygulanıyor:

| Durum | HTTP | code |
| --- | --- | --- |
| Bean validation fail | 400 | `INVALID_QUERY` |
| Request body çok büyük | 413 | `PAYLOAD_TOO_LARGE` |
| Provider istisnası | 502 | `PROVIDER_ERROR` |
| DB istisnası | 503 | `DATABASE_UNAVAILABLE` |
| Diğer her şey | 500 | `INTERNAL_ERROR` |

Validation geçtikten sonra `SearchRequest.toQuery()` `SearchQuery` döndürüyor, controller bunu `SearchService.search()`'e gönderiyor.

**`DefaultSearchService`** (`application/search/`):

```java
@Cacheable(cacheNames = "search",
           keyGenerator = "searchCacheKeyGenerator",
           unless = "#result == null")
public SearchResult search(SearchQuery query) { … }
```

`SearchCacheKeyGenerator` (`infrastructure/cache/`) `(q, type, sort, page, limit)` 5'lisinden stable bir string key üretiyor — null'lar `_NONE_` olarak işaretleniyor, key collision yok.

İki farklı yol:

1. **Cache hit** → Spring Cache abstraction Redis'ten dönüyor, `ContentRepository` çağrılmıyor. TTL default 300 sn (`cache.search.ttl-seconds`).
2. **Cache miss** → repository çağrılıyor, sonuç hem caller'a hem cache'e yazılıyor. `unless = "#result == null"` istisna durumunda yazılmamasını sağlıyor.

`cache.search.enabled=false` ise NoOpCacheManager devreye giriyor; her sorgu DB'ye gidiyor. Redis ulaşılamazsa `ResilientCacheManager` (`infrastructure/cache/`) sessizce repository'ye düşüyor — outage'da hata değil yavaşlama oluyor.

**Dashboard** (`web/dashboard/`) farklı yol: `DashboardController` `ContentRepository.search()`'ü doğrudan çağırıyor (HTML render eden tarafı). `?sort=invalid` veya `?type=invalid` gönderirsen tolerant parsing devreye giriyor: değer ignore ediliyor + sayfaya görünür "ignored" notice basılıyor.

## 5) Cross-cutting concerns

**Sync scheduler** — `application/scheduler/SyncScheduler`. `@Scheduled(fixedDelayString="${aggregator.sync.fixed-delay-ms:300000}")` ile her 5 dk'da bir `ContentAggregator.runSync()` tetikliyor. `aggregator.sync.enabled=false` ise scheduler hiç oluşmuyor. Çakışan run'ları engellemek için `AtomicBoolean` ile reentrancy guard var.

**Rate limiting** — `infrastructure/ratelimit/RateLimitFilter`. Bucket4j 100 req / 60 sn / IP varsayılanıyla. Buckets Redis'te tutuluyor, böylece app instance'larından bağımsız sayım. Limit aşılırsa 429 + `Retry-After` header. `ratelimit.enabled=false` iken `LoggingOnlyRateLimitFilter` devreye giriyor — istekleri geçiriyor ama "şuna 429 atardım" log'u düşüyor (limit tuning için).

**Request ID propagation** — `infrastructure/logging/RequestIdFilter`. Her HTTP isteğinde `X-Request-Id` header'ı varsa onu, yoksa yeni UUID üretip MDC'ye `requestId` olarak koyuyor. Tüm log satırlarında bu alan görünüyor (`logback-spring.xml` JSON encoder ile çıkartıyor). Response header'ında da geri dönüyor — uçtan uca trace edebilesin diye.

**OpenAPI / Swagger** — `web/openapi/OpenApiConfig`. SpringDoc kullanıyor. `/swagger-ui.html` ve `/v3/api-docs` endpoint'leri otomatik geliyor.

**Secret redaction** — `infrastructure/config/`. `ErrorMessageSanitizer` ve `SecretRedactingPropertySource` log ve hata mesajlarından konfigüre edilmiş gizli değerleri (DB password, provider URL'leri) maskeliyor.

## 6) İşlevsel Olmayan Gözlemlenebilirlik

Bu bölüm, uygulamanın operasyonel görünürlüğünü sağlayan bileşenleri anlatıyor: metrikler, sağlık durumu, analitik ve dışa aktarım. Hepsi mevcut Clean Architecture kurallarına uygun — domain'e dokunmuyor, web katmanı infrastructure'ı görmüyor.

### Prometheus metrikleri — `/actuator/prometheus`

Spring Boot Actuator + Micrometer + `micrometer-registry-prometheus` üçlüsüyle çalışıyor. `GET /actuator/prometheus` endpoint'i Prometheus text formatında (`text/plain; version=0.0.4`) tüm meter'ları sunuyor.

Meter taksonomisi:

| Meter adı | Tip | Tag'ler | Açıklama |
| --- | --- | --- | --- |
| `provider_fetch_duration_seconds` | Timer | `provider`, `outcome` | Provider fetch süresi (success/failure) |
| `provider_fetch_failures_total` | Counter | `provider` | Başarısız fetch sayısı |
| `search_query_duration_seconds` | Timer | `cache_hit` | Arama sorgusu süresi |
| `search_cache_hits_total` | Counter | — | Cache hit sayısı |
| `search_cache_misses_total` | Counter | — | Cache miss sayısı |
| `ingest_items_inserted_total` | Counter | `provider` | Yeni eklenen içerik sayısı |
| `ingest_items_updated_total` | Counter | `provider` | Güncellenen içerik sayısı |
| `ingest_items_rejected_total` | Counter | `provider`, `reason` | Reddedilen içerik sayısı |
| `ratelimit_blocked_total` | Counter | `path` | Rate limit'e takılan istek sayısı |

Önemli güvenlik kuralı: hiçbir metrik tag'inde kullanıcı PII'si (arama terimi `q`, `requestId`, IP adresi) yer almaz. Bu kural `MetricsPiiPropertyTest` ile jqwik 200 iterasyonla doğrulanıyor.

Instrumentasyon stratejisi: `infrastructure/metrics/` altında küçük `@Component` sınıfları (`ProviderFetchMetrics`, `SearchMetrics`, `IngestMetrics`, `RateLimitMetrics`) meter'ları tutuyor ve açık `recordX(...)` metotları sunuyor. AOP yok, gizli davranış yok — çağrı noktasından meter adını arayabilirsin.

### Provider sağlık durumu — `/api/v1/admin/providers`

Operatörün her provider'ın son senkronizasyon durumunu log okumadan görmesini sağlıyor. `GET /api/v1/admin/providers` çağrıldığında dönen JSON:

```json
{
  "providers": [
    {
      "name": "provider1-json",
      "lastSyncAt": "2024-06-15T10:30:00Z",
      "lastSyncOutcome": "success",
      "lastFetchedItems": 42,
      "totalSuccesses": 128,
      "totalFailures": 3,
      "lastErrorMessage": null
    }
  ]
}
```

`ProviderHealthDto` şeması:

| Alan | Tip | Açıklama |
| --- | --- | --- |
| `name` | String | Provider bean adı |
| `lastSyncAt` | ISO-8601 / null | Son sync zamanı (UTC) |
| `lastSyncOutcome` | `"success"` / `"failure"` / null | Son sync sonucu |
| `lastFetchedItems` | int | Son sync'te çekilen item sayısı |
| `totalSuccesses` | long | Toplam başarılı sync sayısı |
| `totalFailures` | long | Toplam başarısız sync sayısı |
| `lastErrorMessage` | String / null | Son hata mesajı (sanitize edilmiş, maks 256 karakter) |

Durum `ProviderHealthRegistry` (`infrastructure/admin/`) içinde process-local `ConcurrentHashMap` ile tutuluyor. `DefaultContentAggregator` her fetch sonucunda bu registry'yi güncelliyor.

### Manuel senkronizasyon tetikleme — `POST /api/v1/admin/sync`

Scheduler'ı beklemeden anlık sync başlatmak için:

```
POST /api/v1/admin/sync
X-Admin-Token: <token>
```

Yanıt (HTTP 202 Accepted):

```json
{ "triggered": true, "alreadyRunning": false }
```

Eğer halihazırda bir sync çalışıyorsa:

```json
{ "triggered": false, "alreadyRunning": true }
```

`SyncCoordinator` (`infrastructure/sync/`) `AtomicBoolean` guard'ını hem `SyncScheduler` hem `AdminSyncController` için paylaşıyor. Gerçek `runSync()` çağrısı Spring `TaskExecutor` üzerinde ayrı thread'de çalışıyor — HTTP isteği hemen 202 ile dönüyor. Sync tamamlandığında `@CacheEvict(allEntries=true)` tetikleniyor ve arama cache'i temizleniyor.

### Arama analitiği (Search Analytics)

Her `GET /api/v1/search` çağrısı sonrasında bir `SearchAnalyticsRecord` kaydediliyor:

| Alan | Açıklama |
| --- | --- |
| `requestedAt` | İstek zamanı (ISO-8601) |
| `q` | Arama terimi |
| `type` | İçerik tipi filtresi (nullable) |
| `sort`, `page`, `limit` | Sayfalama/sıralama parametreleri |
| `totalResults` | Bulunan sonuç sayısı (5xx'te null) |
| `latencyMs` | Sorgu süresi (ms) |
| `cacheHit` | Cache'ten mi geldi |
| `requestId` | MDC request ID |
| `clientIpHash` | SHA-256 hex hash (PII saklanmıyor) |
| `errorCode` | 5xx durumunda hata kodu |

Üç sink implementasyonu var (`infrastructure/analytics/`):

- **`JdbcSearchAnalyticsSink`** (varsayılan) — `search_analytics` tablosuna `JdbcTemplate` ile INSERT. Tablo `V2__search_analytics.sql` Flyway migration'ı ile oluşturuluyor.
- **`LogSearchAnalyticsSink`** — tek satır structured log (INFO, `searchAnalytics` marker).
- **`NoOpSearchAnalyticsSink`** — hiçbir şey yapmıyor (`analytics.search.enabled=false` veya `sink=none`).

Kayıt **best-effort**: sink hata verirse `SearchAnalyticsRecorder` uyarı loglar ve kullanıcıya dönen arama yanıtı etkilenmez. 4xx validation hatalarında kayıt yapılmaz; 5xx hatalarında `errorCode` alanıyla birlikte kayıt düşer.

Sink seçimi `analytics.search.sink` property'si ile yapılıyor: `db` | `log` | `none`.

### CSV / JSON dışa aktarım

Arama sonuçlarını dosya olarak indirmek için iki endpoint:

```
GET /api/v1/search.csv?q=…&type=…&sort=…&limit=…
GET /api/v1/search.json?q=…&type=…&sort=…&limit=…
```

**CSV** — `Content-Type: text/csv; charset=UTF-8`, UTF-8 BOM prefix (Excel uyumluluğu), `Content-Disposition: attachment; filename="search-{yyyyMMdd-HHmmss}.csv"`. İlk satır header: `id,title,type,score,publishedAt`. RFC 4180 escaping uygulanıyor (virgül, tırnak, satır sonu içeren alanlar çift tırnak ile sarılıyor). `StreamingResponseBody` ile sabit bellek kullanımı.

**JSON** — `Content-Type: application/json; charset=UTF-8`, mevcut `SearchResponse.data[]` şemasıyla aynı yapıda JSON array.

`limit` belirtilmezse varsayılan 100; `export.search.max-rows` (varsayılan 1000) aşılırsa HTTP 400 `INVALID_QUERY` envelope'u dönüyor.

Dashboard'da (`/dashboard`) iki "İndir" linki mevcut — CSV ve JSON — aktif `sort` ve `type` parametrelerini koruyarak, keyword yoksa `q=*` placeholder kullanarak.

### Admin kimlik doğrulama — `X-Admin-Token`

`/api/v1/admin/*` altındaki tüm endpoint'ler `AdminAuthFilter` (`infrastructure/admin/`) ile korunuyor. İstek header'ında `X-Admin-Token` değeri `ADMIN_API_TOKEN` env değişkeniyle eşleşmeli.

- Eşleşmezse → HTTP 401 `UNAUTHORIZED` standart hata envelope'u.
- Karşılaştırma `MessageDigest.isEqual` ile yapılıyor (timing attack koruması).
- `admin.auth.token` boşsa admin yüzeyi devre dışı kalıyor (geliştirme ortamı kolaylığı).
- Token `SecretRedactingPropertySource` tarafından loglardan otomatik maskeleniyor.

Neden Spring Security değil: admin yüzeyi küçük (iki endpoint), statik token yeterli. İleride JWT'ye geçiş mevcut API'yi bozmadan yapılabilir.

## 7) Konfigürasyon

Default değerler `src/main/resources/application.yaml`'de, her tunable değer environment variable ile override edilebilir (Spring relaxed binding). `local` profili (`application-local.yaml`) developer için localhost defaultlarını veriyor.

**Zorunlu env değişkenleri** — yoksa `ConfigValidator` startup'ı erken fail ediyor (fast-fail):

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
- `PROVIDER_JSON_URL` (`PROVIDERS_JSON_BASE_URL` formu da kabul)
- `PROVIDER_XML_URL` (`PROVIDERS_XML_BASE_URL` formu da kabul)

Bunlar git'e commit edilmiyor — `.env` (gitignore'da) veya CI secret store kullanılıyor. `docker-compose.yml` `${VAR}` interpolasyonuyla okuyor.

İsteğe bağlı tunable'lar README'deki tabloda — TTL, sync interval, rate limit window, vs. Hepsi `@ConfigurationProperties` sınıfları ile bind ediliyor (`infrastructure/config/`).

## 8) Test stratejisi

Test class isim soneki Maven plugin'e gidiş yolunu belirliyor:

| Sonek | Plugin | İçerik |
| --- | --- | --- |
| `*Test.java` | Surefire | JUnit 5 unit testleri, ArchUnit kuralları |
| `*PropertyTest.java` | Surefire | jqwik property-based testler (≥ 100 iterasyon) |
| `*IT.java` | Failsafe | Testcontainers integration testleri (Docker gerekli) |

Critical property test'ler:

- Skorlama formülü (`ScoringEnginePropertyTest`) — 200 iterasyon, formül 1e-9 toleransla doğrulanıyor.
- Skorlama determinism — aynı girdi için bit düzeyinde aynı sonuç.
- Provider round-trip (`JsonProviderRoundTripPropertyTest`, `XmlProviderRoundTripPropertyTest`) — DTO serialize/deserialize equivalency.
- Repository upsert idempotence (`ContentRepositoryUpsertIT`) — Testcontainers ile gerçek Postgres'e karşı.
- Cache semantics (`SearchServiceCachePropertyTest`) — repeat queries cache'e gidiyor mu, evict sonrası gerçekten gidiyor mu, vs.
- Failure isolation (`ContentAggregatorPropertyTest`) — random N×M provider/item matrisi, her valid item upsert ediliyor mu, hiçbir exception escape ediyor mu.
- Rate limit transition, request-id MDC propagation, sanitization, vs.

## 9) Build ve çalıştırma

```cmd
:: Hızlı build (testleri atla)
mvnw.cmd -B -DskipTests package

:: Unit + property + ArchUnit
mvnw.cmd -B test

:: Tam doğrulama (Failsafe = Testcontainers, Docker gerekli)
mvnw.cmd -B verify

:: Local çalıştırma
set SPRING_PROFILES_ACTIVE=local
mvnw.cmd spring-boot:run
```

Docker stack tercih edersen: `.env` dosyasını oluştur (zorunlu env değişkenleri için), sonra `docker compose up --build`. Stack üç servis: `app` (8080), `postgres:16` (5432), `redis:7-alpine` (6379). Healthcheck'ler depend_on ile zincirlenmiş — postgres ve redis healthy olmadan app başlamıyor.

## Kafanda canlandırması için tipik bir istek

Diyelim sen `GET /api/v1/search?q=docker&type=video&sort=score&page=1&limit=5` çağırdın. Olan biten sırayla:

1. **`RequestIdFilter`** UUID üretir, MDC'ye `requestId` koyar, response header'ına da yazar.
2. **`RateLimitFilter`** IP'nin Redis bucket'ından 1 token tüketmeye çalışır. Yetmiyorsa 429 + `Retry-After`.
3. **`RequestSizeLimitFilter`** payload sınırını kontrol eder.
4. **Spring MVC** `SearchController.search`'a yönlendirir. `@Valid SearchRequest` parametreleri DTO'ya bind eder; `q="docker"`, `type="video"`, vs.
5. Validation geçer (q boş değil, length 1-200 arası, type pattern uyuyor, page ≥ 1, limit ≤ 100).
6. **`SearchRequest.toQuery()`** `SearchQuery` döner.
7. **`SearchService.search`** `@Cacheable` proxy ile sarmalı. Cache key generator `(docker, video, score, 1, 5)` tuple'ından stable string üretir.
8. Cache miss → **`DefaultSearchService.search`** çalışır, `ContentRepository.search(SearchCriteria)` çağırır.
9. **`ContentRepositoryAdapter`** `SearchCriteria`'yı parametrelere çevirip **`ContentJpaRepository.searchFts`** native query'sini çalıştırır. `plainto_tsquery('simple', 'docker')` GIN index'ten sonuçları çekiyor, `type='VIDEO'` filtresi uygulanıyor, `final_score DESC, id ASC` sıralanıyor, OFFSET 0 LIMIT 5 alınıyor.
10. Bir count query da `pagination.total` için çalışıyor.
11. Sonuçlar `Content` listesine map ediliyor, `SearchResult` döner.
12. `@Cacheable` sonucu Redis'e yazıyor (TTL 300 sn).
13. Controller `SearchResponse` JSON'unu render ediyor.
14. **`RequestIdFilter`** finally bloğunda MDC'yi temizliyor, response gidiyor.

Aynı sorgu 5 sn sonra geldiğinde 7. adımdaki cache hit'leyecek; provider'lar veya DB'ye dokunulmayacak. 5 dakika sonra scheduler `runSync` çalışırsa `@CacheEvict` cache'i süpürür ve bir sonraki sorgu DB'den taze veri çeker.

## Kod gezerken takılacağın yerler

- **"Bu ne neden record/sealed?"** Domain DTO'ları immutable olsun ve switch expression'larla exhaustive davranabilelim diye. `NormalizationResult` sealed interface'i `Accepted | Rejected` ile pattern matching'e izin veriyor.
- **"Neden Content/ContentEntity ikilisi?"** Domain çerçeve-bağımsız kalsın diye. Mapping tek yerde (`ContentRepositoryAdapter`).
- **"`evaluationAt` neden parametre?"** Skorlama deterministic olsun ve test edilebilsin diye. `Clock` bean'i kullanıldığı için testlerde fixed clock enjekte edebiliyoruz.
- **"Neden `plainto_tsquery`?"** Kullanıcı input'unu otomatik escape ediyor; `to_tsquery` direkt operatörlü dilbilim bekliyor ve injection'a açık.
- **"`@CacheEvict` neden `runSync`'te?"** Sync sonrası DB içeriği değişebilir; cache'i bayatlatmamak için.
- **"ArchUnit testleri neden var?"** Yeni bir Spring import'u istemeden domain'e sızdırırsan build kırılır. Mimariyi dokümantasyon değil, test kuvvetli tutuyor.

Daha derin gitmen gereken yerler için baktığın klasör yeterli — her sınıfın Javadoc'unda hangi requirement'ı karşıladığı (`REQ N.M`) yazıyor; spec dosyaları (`.kiro/specs/search-engine-service/requirements.md` ve `design.md`) bu numaraları açıklıyor.
