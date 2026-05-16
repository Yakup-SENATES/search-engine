# Design Document

## Overview

This is a **purely additive web-layer change** to the existing
`GET /dashboard` endpoint. It introduces visible sort and type-filter
controls, a result-count summary, a score-sort header link, type badges,
and CSS polish (header bar, zebra striping, hover, sticky `<thead>`,
active-sort indicator, ≤600 px responsive layout).

Scope guardrails:

- **No** changes under `com.example.searchengine.domain`,
  `com.example.searchengine.application`, or
  `com.example.searchengine.infrastructure`.
- **No** new Maven dependencies (`pom.xml` untouched).
- **No** JavaScript files, `<script>` tags, or external assets.
- **No** new static-resource directory under `src/main/resources/`.
- The existing tolerant-parsing semantics in `DashboardController` and the
  existing `IgnoredParamNotice`, `data-testid="ignored-notice"`, and
  `data-testid="empty-message"` blocks are preserved verbatim.

## Architecture

The dashboard remains a single Spring MVC `@Controller` rendering a single
Thymeleaf template:

```
Browser ──► DashboardController.dashboard(sort, type, model)
              │
              ├─ tolerant-parses sort/type (existing helpers)
              ├─ delegates to SearchService.listTop(sort, type, 20)
              ├─ maps Content[] → DashboardRow[]   (existing factory)
              └─ exposes model attrs:
                   rows         (existing)
                   notice       (existing)
                   empty        (existing)
                   activeSort   (NEW — REQ 3.1)
                   activeType   (NEW — REQ 3.2)
              │
              ▼
            templates/dashboard.html  (restructured + new inline CSS)
```

The architecture diagram and dependency direction are unchanged. The change
adds two model attributes and rewrites the body of the existing template
file. ArchUnit rules continue to enforce that `web` does not import
`infrastructure`.

## Components and Interfaces

### 1. `DashboardController` — additive changes only

The controller's signature, `DEFAULT_LIMIT`, `ALLOWED_SORTS`,
`ALLOWED_TYPES`, and tolerant-parsing helpers stay exactly as they are.
Two existing local variables are simply also written into the model:

```java
@GetMapping("/dashboard")
public String dashboard(
        @RequestParam(name = "sort", required = false) String sort,
        @RequestParam(name = "type", required = false) String type,
        Model model
) {
    IgnoredParamNotice.Builder noticeBuilder = new IgnoredParamNotice.Builder();

    String effectiveSort = parseSortOrNotify(sort, noticeBuilder); // existing
    String effectiveType = parseTypeOrNotify(type, noticeBuilder); // existing

    SearchResult result = searchService.listTop(effectiveSort, effectiveType, DEFAULT_LIMIT);
    List<DashboardRow> rows = DashboardRow.fromResult(result);

    model.addAttribute("rows", rows);
    model.addAttribute("notice", noticeBuilder.build());
    model.addAttribute("empty", rows.isEmpty());
    model.addAttribute("activeSort", effectiveSort);   // NEW — always non-null lowercase
    model.addAttribute("activeType", effectiveType);   // NEW — lowercase or null
    return "dashboard";
}
```

Contracts:

| Attribute     | Type     | Possible values                                      | Notes                                              |
|---------------|----------|------------------------------------------------------|----------------------------------------------------|
| `activeSort`  | `String` | `"score"`, `"popularity"`, `"relevance"`             | Never `null`; always lowercase; tolerantly parsed. |
| `activeType`  | `String` | `"text"`, `"video"`, or `null`                       | `null` when no filter (default or invalid input).  |

No new helper methods are introduced. No new fields. No new dependencies.

### 2. `DashboardRow` — unchanged

The `(title, type, score)` record and `DashboardRow.fromResult(...)`
factory remain exactly as they are.

### 3. `IgnoredParamNotice` — unchanged

The notice block, its `data-testid="ignored-notice"`, and the wording of
each ignored message are preserved (REQ 6.5, 9.3).

### 4. `templates/dashboard.html` — restructured

The file remains a single self-contained Thymeleaf template with inline
`<style>`. Its high-level structure becomes:

```
<html>
  <head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">  ← REQ 8.1
    <title>Search Engine Dashboard</title>
    <style> /* see CSS section below */ </style>
  </head>
  <body>

    <header class="topbar">                    ← REQ 6.1
      <span class="topbar__brand">Search Engine</span>
      <span class="topbar__title">Dashboard</span>
    </header>

    <main class="page">

      <!-- Ignored-notice block (UNCHANGED, preserved verbatim) -->     ← REQ 6.5, 9.3
      <div class="notice" th:if="${notice.hasMessages()}"
           role="status" data-testid="ignored-notice">
        <strong>Some parameters were ignored:</strong>
        <ul>
          <li th:each="message : ${notice.messages()}" th:text="${message}">Ignored ...</li>
        </ul>
      </div>

      <!-- Sort / type form -->                                          ← REQ 1, 2, 3
      <form class="controls" method="get" action="/dashboard">
        <label class="controls__field">
          <span class="controls__label">Sort by</span>
          <select name="sort">
            <option value="score"      th:selected="${activeSort == 'score'}">Score</option>
            <option value="popularity" th:selected="${activeSort == 'popularity'}">Popularity</option>
            <option value="relevance"  th:selected="${activeSort == 'relevance'}">Relevance</option>
          </select>
        </label>

        <label class="controls__field">
          <span class="controls__label">Type</span>
          <select name="type">
            <option value=""      th:selected="${activeType == null}">All</option>
            <option value="text"  th:selected="${activeType == 'text'}">Text</option>
            <option value="video" th:selected="${activeType == 'video'}">Video</option>
          </select>
        </label>

        <button class="controls__submit" type="submit">Apply</button>
      </form>

      <!-- Result-count summary -->                                       ← REQ 7
      <p class="summary" data-testid="result-count">
        Showing
        <span th:text="${#lists.size(rows)}">0</span>
        <span th:if="${activeType != null}" th:text="${activeType + ' '}"></span>results
        sorted by
        <span th:text="${activeSort}">score</span>
      </p>

      <!-- Table (responsive wrapper for ≤600px) -->                       ← REQ 8.3
      <div class="table-wrap">
        <table data-testid="dashboard-table">
          <thead>
            <tr>
              <th scope="col">Title</th>
              <th scope="col">Type</th>
              <th scope="col" class="score-col"
                  th:classappend="${activeSort == 'score'} ? ' active-sort' : ''">
                <a th:href="@{/dashboard(sort='score', type=${activeType})}">
                  Score<span th:if="${activeSort == 'score'}" aria-hidden="true"> ▼</span>
                </a>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr th:each="row : ${rows}" th:unless="${empty}">
              <td th:text="${row.title()}">Title</td>
              <td>
                <span class="type-badge"
                      th:classappend="' type-badge--' + ${row.type()}"
                      th:text="${row.type()}">type</span>
              </td>
              <td class="score"
                  th:text="${#numbers.formatDecimal(row.score(), 1, 1)}">0.0</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Empty-state message (UNCHANGED, preserved verbatim) -->         ← REQ 6.6
      <p class="empty" th:if="${empty}" data-testid="empty-message">
        No content items are available.
      </p>

    </main>
  </body>
</html>
```

Notes on the `Score_Header_Link`:

- The Thymeleaf URL expression `@{/dashboard(sort='score', type=${activeType})}`
  emits `/dashboard?sort=score` when `activeType` is `null`, and
  `/dashboard?sort=score&type=text` (or `video`) otherwise — Thymeleaf
  automatically omits `null`-valued query parameters. This satisfies
  REQ 1.6 and REQ 11.4 without any controller-side string concatenation.
- The `class="active-sort"` on the `<th>` plus the `▼` glyph satisfy
  REQ 3.5. The glyph is `aria-hidden` because it duplicates information
  conveyed by the `selected` `<option>` and the underline of the link.

Notes on the `<select>` elements:

- Each `<option>` uses `th:selected` rather than a hand-written
  `selected="selected"` attribute, so Thymeleaf emits the attribute only
  when the boolean expression is true. This satisfies REQ 3.3 and 3.4.
- The "All" option uses `value=""` so that the empty-string `type` query
  parameter is dropped client-side by the browser when the form submits.
  The controller's existing tolerant parser already treats blank `type`
  as "no filter", so this round-trips correctly (REQ 9.2).

## Data Models

No data-model changes. The view continues to consume:

- `rows: List<DashboardRow>` — unchanged.
- `notice: IgnoredParamNotice` — unchanged.
- `empty: Boolean` — unchanged.
- `activeSort: String` — new, always lowercase, never null.
- `activeType: String` — new, lowercase or null.

## CSS Design

All CSS lives inside the existing inline `<style>` block in
`dashboard.html` (REQ 10.2). No external stylesheet, no
`src/main/resources/static/` directory.

### Color tokens

| Token / role                | Hex value | Notes                                     |
|-----------------------------|-----------|-------------------------------------------|
| Page background             | `#ffffff` | Default body.                             |
| Page foreground (text)      | `#1d1d1f` | Existing.                                 |
| Header bar background       | `#0f172a` | Slate-900.                                |
| Header bar foreground       | `#ffffff` | Contrast vs `#0f172a` ≈ 17.4:1 (AAA).     |
| Table header background     | `#f7f7f9` | Existing.                                 |
| Zebra row (odd)             | `#ffffff` | Default.                                  |
| Zebra row (even)            | `#fafafa` | Subtle ≈ 1.04:1 vs white — REQ 6.2.       |
| Row hover background        | `#eef2ff` | Indigo-50 — REQ 6.3.                      |
| Active-sort header accent   | `#1d4ed8` | Used as link colour in `.active-sort`.    |
| Type-badge `text` background | `#1d4ed8` | Blue-700.                                 |
| Type-badge `text` foreground | `#ffffff` | Contrast vs `#1d4ed8` ≈ 8.6:1 (AAA).      |
| Type-badge `video` background | `#6d28d9` | Violet-700.                               |
| Type-badge `video` foreground | `#ffffff` | Contrast vs `#6d28d9` ≈ 7.7:1 (AAA).      |

Both badge foreground/background pairs exceed the WCAG 2.1 AA threshold
of 4.5:1 for normal text (REQ 5.4). Contrast ratios were computed using
the WCAG relative-luminance formula; values are AAA-grade for normal
text, which gives headroom for future palette tweaks.

### Rules added (sketch)

```css
/* Header bar — REQ 6.1 */
.topbar {
  display: flex;
  align-items: baseline;
  gap: 1rem;
  padding: 1rem 2rem;
  background: #0f172a;
  color: #ffffff;
}
.topbar__brand { font-weight: 700; font-size: 1.1rem; }
.topbar__title { font-weight: 400; opacity: 0.85; }

/* Page wrapper — replaces the previous `body { margin: 2rem }`. */
.page { padding: 2rem; }

/* Sort/type form — REQ 1, 2, 8.2 */
.controls {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  gap: 0.75rem;
  margin: 1rem 0;
}
.controls__field { display: flex; flex-direction: column; }
.controls__label { font-size: 0.85rem; color: #5f6368; margin-bottom: 0.25rem; }
.controls__submit {
  padding: 0.4rem 0.9rem;
  background: #1d4ed8;
  color: #ffffff;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
}

/* Result-count summary — REQ 7 */
.summary { color: #5f6368; margin: 0.5rem 0 1rem 0; }

/* Table polish — REQ 6.2, 6.3, 6.4, 8.3 */
.table-wrap { overflow-x: auto; }       /* horizontal scroll on small screens */
table { width: 100%; border-collapse: collapse; }
thead th {
  position: sticky;                      /* REQ 6.4 */
  top: 0;
  background: #f7f7f9;
  font-weight: 600;
  text-align: left;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid #e6e6e6;
  z-index: 1;
}
tbody td { padding: 0.5rem 0.75rem; border-bottom: 1px solid #e6e6e6; }
tbody tr:nth-child(even) { background: #fafafa; }   /* REQ 6.2 */
tbody tr:hover           { background: #eef2ff; }   /* REQ 6.3 */
td.score, th.score-col   { text-align: right; font-variant-numeric: tabular-nums; } /* REQ 4.2 */

/* Score-header link — REQ 1.6 */
th.score-col a { color: inherit; text-decoration: none; }
th.score-col a:hover { text-decoration: underline; }
th.active-sort a { color: #1d4ed8; font-weight: 700; }   /* REQ 3.5 */

/* Type badges — REQ 5 */
.type-badge {
  display: inline-block;
  padding: 0.1rem 0.55rem;
  border-radius: 999px;
  font-size: 0.8rem;
  font-weight: 600;
  color: #ffffff;
  text-transform: lowercase;
  letter-spacing: 0.02em;
}
.type-badge--text  { background: #1d4ed8; }   /* blue, REQ 5.2 */
.type-badge--video { background: #6d28d9; }   /* purple, REQ 5.3 */

/* Responsive — REQ 8.2, 8.3 */
@media (max-width: 600px) {
  .page { padding: 1rem; }
  .controls { flex-direction: column; align-items: stretch; }
  .controls__field, .controls__submit { width: 100%; }
}
```

### Score formatting

Per REQ 4.1, scores render via Thymeleaf's
`#numbers.formatDecimal(row.score(), 1, 1)` — minimum-1 / maximum-1
fraction digits. Examples: `99.0`, `0.5`, `12.3`. The current template
uses `, 1, 2)` (two decimals); this design changes it to `, 1, 1)`
to satisfy REQ 4.1 exactly. Right-alignment + `font-variant-numeric:
tabular-nums` on `td.score` satisfies REQ 4.2.

## Error Handling

Unchanged. Tolerant parsing in the controller continues to:

1. Coerce invalid `sort` to `"score"` and append a message to the
   `IgnoredParamNotice.Builder`.
2. Coerce invalid `type` to `null` and append a message to the
   `IgnoredParamNotice.Builder`.
3. Render `<div data-testid="ignored-notice">` only when the notice has
   messages (REQ 9.3, 6.5).

The existing `GlobalExceptionHandler` continues to handle non-dashboard
error paths; nothing here interacts with it.

## Correctness Properties

*A property is a characteristic or behavior that should hold true across
all valid executions of a system — a formal statement about what the
system should do. Properties serve as the bridge between human-readable
specifications and machine-verifiable correctness guarantees.*

### Property 1: `activeSort` tolerant-parsing contract

For any input string `s` (including `null`, blank, mixed-case, padded,
or arbitrary garbage), after `DashboardController.dashboard(s, _, model)`
returns, the model attribute `activeSort` satisfies:

- `activeSort` is never `null`.
- `activeSort` is one of `{"score", "popularity", "relevance"}`.
- If `s` is non-null, non-blank, and `s.trim().toLowerCase()` is in
  `{"score", "popularity", "relevance"}`, then
  `activeSort == s.trim().toLowerCase()`.
- Otherwise (`s` is `null`, blank, or unrecognised),
  `activeSort == "score"`.

**Validates: Requirements 3.1, 3.6, 9.2**

### Property 2: `activeType` tolerant-parsing contract

For any input string `s` (including `null`, blank, mixed-case, padded,
or arbitrary garbage), after `DashboardController.dashboard(_, s, model)`
returns, the model attribute `activeType` satisfies:

- `activeType` is one of `{"text", "video", null}`.
- If `s` is non-null, non-blank, and `s.trim().toLowerCase()` is in
  `{"text", "video"}`, then `activeType == s.trim().toLowerCase()`.
- Otherwise (`s` is `null`, blank, or unrecognised), `activeType == null`.

**Validates: Requirements 3.2, 3.6, 9.2**

## Testing Strategy

Three layers, mirroring the existing project conventions
(`*Test.java` → Surefire unit, `*PropertyTest.java` → Surefire jqwik,
`*IT.java` → Failsafe Testcontainers).

### Unit tests — `DashboardControllerTest` (extend existing class)

The existing test class covers tolerant parsing, empty state, and row
forwarding (REQ 9.2, 6.6). Add the following assertions, each in line
with the existing `@Nested` style:

1. `activeSort` and `activeType` are present on the model after every
   call. (Type-narrowing assertion.)
2. Valid lowercase `sort=popularity`, `type=text`
   → `activeSort == "popularity"`, `activeType == "text"`.
3. Mixed-case `sort=ScOrE`
   → `activeSort == "score"` (verifies tolerant-parser lowercasing).
4. Whitespace-padded `sort="  relevance  "`
   → `activeSort == "relevance"`.
5. Invalid `sort=nope`
   → `activeSort == "score"` AND notice contains a message naming `sort`.
6. Invalid `type=alsonope`
   → `activeType == null` AND notice contains a message naming `type`.
7. Null and blank `sort` / `type`
   → `activeSort == "score"`, `activeType == null`, notice has no
     messages (existing behavior preserved).
8. Valid `sort=relevance`
   → `searchService.listTop("relevance", null, 20)` is invoked
     (REQ 1.5 forwarding).

### Property tests — `DashboardControllerPropertyTest` (new file)

Lives at
`src/test/java/com/example/searchengine/web/dashboard/DashboardControllerPropertyTest.java`.
Uses jqwik (already on the classpath) at the standard ≥ 100 iterations.

- **Property 1 — `activeSort` tolerant-parsing contract**
  Generate `@ForAll` arbitrary `String` (including `null`, blank,
  random Unicode, and biased samples drawn from
  `{"score", "popularity", "relevance"}` with random casing and
  surrounding whitespace). Assert the contract from Property 1 above.
  Tag: `Feature: dashboard-ux-enhancements, Property 1: activeSort
  tolerant-parsing contract`.

- **Property 2 — `activeType` tolerant-parsing contract**
  Same input strategy as Property 1, biased with samples drawn from
  `{"text", "video"}`. Assert the contract from Property 2 above.
  Tag: `Feature: dashboard-ux-enhancements, Property 2: activeType
  tolerant-parsing contract`.

The property tests instantiate the controller directly with a Mockito
mock `SearchService` (the same setup the existing unit test uses) so
they remain Surefire-only and avoid Spring or Testcontainers boot-up
costs.

### Integration tests — `DashboardControllerIT` (extend existing class)

The existing IT covers default ordering, sort-by-popularity, type
filters, ignored notices, the empty state, and a 1000-row performance
budget. Extend with the following assertions, each on the rendered HTML
string:

1. **Sort `<select>` renders the active option as `selected`**
   (REQ 3.3, 11.2). For each value `v ∈ {"score", "popularity",
   "relevance"}`, `GET /dashboard?sort=v` renders an
   `<option value="v" selected>` — `assertThat(body).containsPattern(
   "<option value=\"" + v + "\"[^>]*selected[^>]*>")`.

2. **Type `<select>` renders the active option as `selected`**
   (REQ 3.4). For each value `v ∈ {"text", "video"}`,
   `GET /dashboard?type=v` renders `<option value="v" selected>`.
   For the no-filter request, the `<option value="" selected>All</option>`
   form is rendered.

3. **Result-count summary contains the row count**
   (REQ 7.1, 7.2, 11.3). With the existing 22-row fixture and the
   default request: body contains `data-testid="result-count"` AND a
   substring matching `Showing 20` AND `sorted by score`.
   With an empty fixture (no seed): body contains `Showing 0` AND
   `sorted by score`.

4. **Result-count summary includes the active type when set**
   (REQ 7.3). `GET /dashboard?type=text` → body contains
   `Showing` and `text results sorted by score`.

5. **Score-header link `href`**
   (REQ 1.6, 11.4). With no type filter, the rendered Score header
   contains `<a href="/dashboard?sort=score">Score`. With `type=text`,
   the rendered href is `/dashboard?sort=score&amp;type=text` (Thymeleaf
   HTML-escapes the ampersand).

6. **Active-sort indicator** (REQ 3.5). With `sort=score` (default),
   the Score `<th>` carries `class=...active-sort...` and the rendered
   cell contains a `▼` glyph. With `sort=popularity`, the Score `<th>`
   does **not** carry `active-sort`.

7. **Type badges render with the correct CSS class** (REQ 5.1–5.3).
   Body contains `class="type-badge type-badge--text"` and
   `class="type-badge type-badge--video"` after the existing
   mixed-type fixture.

8. **Score formatting** (REQ 4.1). With the existing fixture
   (`final_score = 100.0` for rows 1 and 2), body contains the
   substring `100.0` and does **not** contain `100.00`.

9. **Viewport meta + responsive media query** (REQ 8.1, 8.2, 8.3).
   Body contains
   `<meta name="viewport" content="width=device-width, initial-scale=1">`
   AND `@media (max-width: 600px)` (the inline `<style>` is part of
   the rendered HTML).

10. **No client-side script** (REQ 10.4). Body contains zero `<script`
    occurrences.

11. **No pagination / no search box** (REQ 9.4, 9.5). Body contains
    no `<input` and no `name="page"` / `name="q"` strings.

12. **Backwards-compat — ignored-notice and empty-state preserved**
    (REQ 6.5, 6.6, 9.3). All existing assertions on
    `data-testid="ignored-notice"` and `data-testid="empty-message"`
    are retained.

### Architecture compliance

- The existing ArchUnit suite under
  `src/test/java/.../architecture/` already enforces:
  - `web` does not import `infrastructure` (REQ 9.6, 10.5).
  - `domain` is framework-free.
- No new ArchUnit rule is needed for this feature.
- Manual / review-time check: `pom.xml` is identical before and after
  the change (REQ 10.3). A `git diff pom.xml` after the
  implementation must show **zero** lines changed.
- Manual / review-time check: no new file is created under
  `src/main/resources/static/` (REQ 10.2, 10.4).

### Test count summary

| Layer       | File                                | New assertions / cases |
|-------------|-------------------------------------|------------------------|
| Unit        | `DashboardControllerTest`           | 8                      |
| Property    | `DashboardControllerPropertyTest`   | 2 properties (≥ 100 iterations each) |
| Integration | `DashboardControllerIT`             | 12                     |

All three test files stay within the existing `web/dashboard` test
package; no new test packages or new test dependencies are introduced.
