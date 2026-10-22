# Phase 21 — Historical queries, DBPulse SQL fingerprint, desktop SQL details, initial CPU graph

**Observed:** Deadlock testing SQL dropped off live Query Store UI later; DBPulse
self-observer SQL appeared among customer queries; the installed SQL text drawer
required an environment variable; CPU trend must render immediately.

## Distinct retention windows

- **Latest 15 minutes:** SQL Server Query Store runtime stats where last
  execution occurred in the past 15 minutes. An existing query can vanish from
  the latest list without being removed from the SQL Server Query Store.
- **Last 7 days — saved DBPulse observations:** query_performance_snapshots
  already persisted locally by previous collectors. A new bounded read-only
  `/api/v1/targets/{id}/query-performance/history?limit=100` deduplicates by
  queryId/planId using the newest collected_at, and respects target separation.
  The scheduled 7-day prune remains. Old rows preceding installation of this
  feature can only be shown if they were actually collected/persisted.
  SQL text and plan XML are NEVER stored in this history.
- A saved Query/Plan can be clicked to fetch SQL text on demand **only if**
  Query Store still exposes that text on the SQL Server. A database may purge it.

## Internal collector classification

The SQL Server-only CASE fingerprint now includes the recently added read-only
Query Store per-database CPU aggregate with its unique
`recorded_cpu_ms` and `recorded_executions` output aliases.
This prevents the DBPulse-owned aggregate from surfacing in the default
customer-query filter. It does NOT blanket-filter legitimate queries that
merely select Query Store/DMV views. Existing historical classifications
are not overwritten.

## SQL text for normal Windows desktop users

Windows Desktop Boot sets `dbpulse.query-text.enabled=true` by default
only in the locally-bound 127.0.0.1 installed environment. Server and Docker
modes keep disabled by default, and `DBPULSE_QUERY_TEXT_ENABLED=false`
is the local desktop opt-out.

SQL text can contain sensitive literals, personal data and tokens. The feature
remains on-demand ONLY after the user selects SQL 보기; the browser supplies
the `X-DBPulse-Local-Request: 1` header. The API rejects remote clients,
nonlocal Host, cross-site Origin/Sec-Fetch-Site or missing header.
No automatic SQL logging, polling, persistence, or query execution.
No claim of resistance to local malicious processes; a strong authentication
model is needed before any LAN/public deployment.

## CPU chart from first paint

The initial chart SVG and axes are present on first view even with no rate
samples. The app explicitly says two distinct Query Store sample intervals
are needed for a derived 1-logical-core-equivalent %. The first sample
is not spoofed to 0%. Historic rate values are not automatically invented.
This is NOT a SQL Server database's precise process CPU percentage.

## Release validation
- [ ] Java SQLite history retention/target isolation, origin-request guards
- [ ] Frontend query history and always-present CPU visual tests
- [ ] Windows installer install/uninstall/DPAPI preservation
- [ ] DBPulseLab live Query Store filter classification
- [ ] Normal installed GUI SQL 보기 and SQL Server Query Store retention semantics
- [ ] Keep PR in draft until user acceptance. No main merge.
