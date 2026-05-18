# feat: operability quick wins (spec + dashboard UX)

This branch lands three independent commits:

1. **`docs(spec)`** — adds the `operability-quick-wins` spec under
   `.kiro/specs/operability-quick-wins/` (requirements + design + tasks)
   covering five additive operability features:
   - Micrometer + Prometheus metrics at `/actuator/prometheus`
   - Admin endpoints: `GET /api/v1/admin/providers` (provider health)
     and `POST /api/v1/admin/sync` (manual sync trigger)
   - Search analytics persisted to a new `search_analytics` table
   - CSV / JSON export at `/api/v1/search.csv` and `/api/v1/search.json`
   - Renovate config + Trivy CI scan
2. **`feat(dashboard)`** — every column header is now a sortable button
   with stacked up/down arrow indicators (▲▼). Click toggles asc/desc;
   the active direction lights up. Sorting runs client-side on the
   already-loaded 20 rows, so no extra round-trip. The existing
   "Top 20 by" select still drives which metric the server pre-sorts on.
3. **`docs`** — adds `COMPLIANCE_REPORT.md` mapping every `whatis.md`
   requirement to the file/line that implements it.

## Why merge before implementation?

The spec is intentionally separated from implementation so the plan can
be reviewed up-front. Section 1 of `tasks.md` is already done (this
branch is `feat/operability-quick-wins`); follow-up commits will tick off
2.x → 8.x against this branch.

## Compatibility

- No existing endpoint contract changes.
- No domain or scoring change. ArchUnit stays green.
- Existing `*Test`, `*PropertyTest`, `*IT` suites stay green.
- Three dashboard IT assertions were updated in commit B to match the
  new markup (sortable headers + `data-value` cells); no behavior
  regression.

## Risks / follow-ups

- `instructions.md` is currently shown as deleted in the working tree
  but is intentionally NOT included in this PR — left for a separate
  decision.
- Admin endpoints will land behind a single `ADMIN_API_TOKEN` static
  token (Spring Security migration tracked as a follow-up spec).
