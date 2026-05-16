# Requirements Document

## Introduction

The existing `GET /dashboard` endpoint renders a minimal Thymeleaf table with
three columns (Title, Type, Score). The user reports that the page looks bare
and lacks an obvious way to sort by score. This feature enhances the dashboard
purely at the web layer: it adds visible sort and type-filter controls that
round-trip through the existing `?sort=` and `?type=` query parameters, makes
the Score column header click-to-sort, and applies visual polish (header bar,
colored type badges, formatted score, zebra rows, hover highlight, sticky
table head, active-sort indicator, result count, responsive layout).

The feature is intentionally additive at the web layer only. It does NOT
modify the domain, application, or infrastructure layers, does NOT introduce
client-side frameworks or new Maven dependencies, and preserves the existing
tolerant-parsing behavior (invalid `sort`/`type` values fall back to defaults
and surface the existing "ignored" notice).

## Glossary

- **Dashboard**: The server-rendered Thymeleaf page served by `GET /dashboard`.
- **DashboardController**: The Spring `@Controller` at
  `com.example.searchengine.web.dashboard.DashboardController`.
- **DashboardView**: The Thymeleaf template
  `src/main/resources/templates/dashboard.html`.
- **DashboardRow**: The existing `(title, type, score)` record passed to the
  template as `rows`.
- **Sort_Parameter**: The `sort` query-string parameter, with allowed values
  `score`, `popularity`, `relevance` (case-insensitive); default `score`.
- **Type_Parameter**: The `type` query-string parameter, with allowed values
  `text`, `video` (case-insensitive); default unset (no filter).
- **Active_Sort**: The effective `Sort_Parameter` value used by the current
  rendering (after tolerant parsing).
- **Active_Type**: The effective `Type_Parameter` value used by the current
  rendering, or unset when no filter applies.
- **Sort_Form**: The HTML `<form method="get" action="/dashboard">` element
  containing the sort and type controls.
- **Score_Header_Link**: The Score column `<th>` rendered as an anchor that
  navigates to `/dashboard` with `sort=score` and the current `type` preserved.
- **Type_Badge**: A styled inline element wrapping each row's type value, with
  a distinct background color per type (`text` → blue, `video` → purple).
- **Result_Count_Summary**: A textual element displaying the number of
  rendered rows and the active filter, for example
  `Showing 12 results sorted by score`.
- **Active_Sort_Indicator**: A visual marker (caret glyph and CSS class)
  placed on the column header matching `Active_Sort`.
- **Ignored_Notice**: The existing notice block rendered when invalid
  parameters are submitted (already implemented; preserved verbatim).
- **DEFAULT_LIMIT**: The existing constant `DashboardController.DEFAULT_LIMIT`
  with value `20`.

## Requirements

### Requirement 1: Visible sort controls

**User Story:** As a dashboard viewer, I want a visible control for choosing
the sort field, so that I can reorder results without editing the URL.

#### Acceptance Criteria

1. THE DashboardView SHALL render a Sort_Form containing a `<select>` named
   `sort` with one `<option>` per allowed value (`score`, `popularity`,
   `relevance`).
2. THE DashboardView SHALL render a submit `<button>` inside the Sort_Form
   that re-issues `GET /dashboard` with the chosen `sort` value as a query
   parameter.
3. WHEN the Sort_Form is submitted with `sort=score`, THE DashboardController
   SHALL render rows ordered by `final_score DESC` with deterministic
   tie-break by `id ASC`.
4. WHEN the Sort_Form is submitted with `sort=popularity`, THE
   DashboardController SHALL render rows ordered according to the existing
   `SearchService.listTop("popularity", …)` contract.
5. WHEN the Sort_Form is submitted with `sort=relevance`, THE
   DashboardController SHALL render rows ordered according to the existing
   `SearchService.listTop("relevance", …)` contract.
6. THE DashboardView SHALL render the Score column header as a
   Score_Header_Link whose `href` is `/dashboard?sort=score` augmented with
   the current `type` query parameter when Active_Type is set.

### Requirement 2: Visible type-filter controls

**User Story:** As a dashboard viewer, I want a visible control for filtering
by content type, so that I can focus on text-only or video-only results.

#### Acceptance Criteria

1. THE DashboardView SHALL render a `<select>` named `type` inside the
   Sort_Form with options `All`, `text`, and `video`.
2. WHEN the Sort_Form is submitted with `type=text`, THE DashboardController
   SHALL render only rows whose type is `text`.
3. WHEN the Sort_Form is submitted with `type=video`, THE DashboardController
   SHALL render only rows whose type is `video`.
4. WHEN the Sort_Form is submitted with the `All` option (empty `type`
   value), THE DashboardController SHALL render rows from both content types.

### Requirement 3: Active selection feedback

**User Story:** As a dashboard viewer, I want the controls to reflect the
current sort and type, so that I can see at a glance how the table is
ordered and filtered.

#### Acceptance Criteria

1. THE DashboardController SHALL expose the model attribute `activeSort`
   containing the effective Active_Sort value as a lowercase string.
2. THE DashboardController SHALL expose the model attribute `activeType`
   containing the effective Active_Type value as a lowercase string, or
   `null` when no type filter is active.
3. WHEN the DashboardView renders the Sort_Form, THE DashboardView SHALL
   mark the `<option>` matching `activeSort` with the `selected` attribute.
4. WHEN the DashboardView renders the Sort_Form, THE DashboardView SHALL
   mark the `<option>` matching `activeType` with the `selected` attribute,
   or mark the `All` option as selected when `activeType` is `null`.
5. THE DashboardView SHALL render an Active_Sort_Indicator (CSS class
   `active-sort` plus a `▼` caret glyph) on the column header matching
   `activeSort`.
6. IF the request supplies an invalid `sort` or `type` value, THEN THE
   DashboardController SHALL set `activeSort` and `activeType` to the
   tolerantly-parsed effective values rather than the raw input.

### Requirement 4: Score formatting

**User Story:** As a dashboard viewer, I want scores rendered to one decimal
place, so that the column is easy to scan.

#### Acceptance Criteria

1. THE DashboardView SHALL render each `DashboardRow.score` value formatted
   to exactly one digit before the decimal minimum and exactly one digit
   after the decimal point (Thymeleaf
   `#numbers.formatDecimal(row.score(), 1, 1)`).
2. THE DashboardView SHALL render score values right-aligned in the Score
   column using tabular numeric figures.

### Requirement 5: Colored type badges

**User Story:** As a dashboard viewer, I want each row's type rendered as a
colored badge, so that I can distinguish text from video at a glance.

#### Acceptance Criteria

1. THE DashboardView SHALL wrap each row's type value in a Type_Badge
   element.
2. WHERE a row's type is `text`, THE DashboardView SHALL apply a blue
   background color to the Type_Badge.
3. WHERE a row's type is `video`, THE DashboardView SHALL apply a purple
   background color to the Type_Badge.
4. THE DashboardView SHALL render the Type_Badge text with sufficient
   contrast against its background to meet WCAG 2.1 AA contrast ratio of at
   least 4.5:1 for normal text.

### Requirement 6: Visual polish

**User Story:** As a dashboard viewer, I want a polished layout, so that the
page feels like part of a real product instead of a debug view.

#### Acceptance Criteria

1. THE DashboardView SHALL render a header bar at the top of the page
   containing the application name `Search Engine` and the page title
   `Dashboard`.
2. THE DashboardView SHALL render alternating background colors on table
   body rows (zebra striping).
3. WHEN the user hovers over a table body row, THE DashboardView SHALL
   apply a hover highlight background color to that row.
4. THE DashboardView SHALL render the table `<thead>` with `position: sticky`
   so that column headers remain visible while scrolling the table body.
5. THE DashboardView SHALL preserve the existing Ignored_Notice block and
   its `data-testid="ignored-notice"` attribute unchanged.
6. THE DashboardView SHALL preserve the existing empty-state message and
   its `data-testid="empty-message"` attribute unchanged.

### Requirement 7: Result count summary

**User Story:** As a dashboard viewer, I want to see how many results are
displayed and which sort is active, so that I have immediate context for the
data on screen.

#### Acceptance Criteria

1. THE DashboardView SHALL render a Result_Count_Summary element with
   `data-testid="result-count"` showing the integer count of rendered rows.
2. THE Result_Count_Summary SHALL include the Active_Sort value in its
   text content, for example `Showing 12 results sorted by score`.
3. WHERE Active_Type is set, THE Result_Count_Summary SHALL include the
   Active_Type value in its text content, for example
   `Showing 7 text results sorted by score`.
4. WHEN no rows are rendered, THE DashboardView SHALL render the
   Result_Count_Summary with a count of `0`.

### Requirement 8: Responsive layout

**User Story:** As a dashboard viewer on a narrower screen, I want the
layout to remain usable, so that I can read results without horizontal
scrolling artifacts.

#### Acceptance Criteria

1. THE DashboardView SHALL declare `<meta name="viewport"
   content="width=device-width, initial-scale=1">` in the document head.
2. WHILE the viewport width is less than or equal to 600 CSS pixels, THE
   DashboardView SHALL stack the Sort_Form controls vertically (one control
   per row).
3. WHILE the viewport width is less than or equal to 600 CSS pixels, THE
   DashboardView SHALL render the table inside a horizontally-scrollable
   container so that no column is clipped.

### Requirement 9: Backwards compatibility

**User Story:** As an operator, I want this enhancement to be a pure
web-layer change, so that existing API behavior, tests, and architecture
rules remain green.

#### Acceptance Criteria

1. THE DashboardController SHALL continue to use `DEFAULT_LIMIT = 20` as the
   row cap on `SearchService.listTop(…)`.
2. THE Dashboard SHALL retain the existing tolerant parsing behavior for
   `sort` and `type` parameters as currently implemented in
   `DashboardController`.
3. IF the request supplies an invalid `sort` or `type` value, THEN THE
   DashboardView SHALL render the existing Ignored_Notice block listing the
   ignored values.
4. THE Dashboard SHALL render no pagination controls in this feature.
5. THE Dashboard SHALL render no free-text search box in this feature.
6. THE feature SHALL NOT modify any source file under
   `com.example.searchengine.domain`,
   `com.example.searchengine.application`, or
   `com.example.searchengine.infrastructure`.

### Requirement 10: Implementation constraints

**User Story:** As a maintainer, I want this enhancement to stay within the
existing tech stack, so that the build, dependency surface, and architecture
rules remain unchanged.

#### Acceptance Criteria

1. THE DashboardView SHALL be rendered as server-side Thymeleaf HTML with
   no client-side single-page-application framework.
2. THE DashboardView SHALL contain its CSS inline within a `<style>` block
   in the same template file.
3. THE feature SHALL NOT add any new dependency to `pom.xml`.
4. THE feature SHALL NOT add any JavaScript file or external script tag to
   the DashboardView.
5. THE DashboardController SHALL remain in the `web` layer and SHALL NOT
   introduce any import from `com.example.searchengine.infrastructure`.

### Requirement 11: Test coverage

**User Story:** As a maintainer, I want the new behavior covered by tests at
both the unit and integration levels, so that regressions are caught
automatically.

#### Acceptance Criteria

1. THE existing `DashboardController` unit test class SHALL be extended with
   assertions that the model contains `activeSort` and `activeType`
   attributes set to the tolerantly-parsed effective values for both valid
   and invalid input.
2. THE existing dashboard integration test (`*IT.java`) SHALL be extended
   with at least one assertion verifying the Sort_Form renders with the
   `selected` attribute on the option matching the request's `sort`
   parameter.
3. THE existing dashboard integration test SHALL be extended with at least
   one assertion verifying the Result_Count_Summary text contains the
   rendered row count.
4. THE existing dashboard integration test SHALL be extended with at least
   one assertion verifying the Score_Header_Link `href` attribute equals
   `/dashboard?sort=score` when no type filter is active.
