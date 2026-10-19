# DBPulse Phase 3 — Incident Detection v0.3.0-alpha

## Purpose
Deliver a **product incident-history feature**, not a training-only load simulator.
DBPulse watches actual MSSQL collector measurements and records incidents in its
own PostgreSQL database.

### Rules
- BLOCKING: blocked requests > 0 in two consecutive 5s collector polls.
- LONG_RUNNING: requests exceeding the configured 1000ms threshold in two consecutive polls.
- COLLECTION_FAILURE: collector or metadata save fails in two consecutive polls.
  This does NOT establish that SQL Server itself is offline.
- Two healthy samples RESOLVE the corresponding event. Failed collection does
  not falsely resolve DB-side incidents, because their states are unknown.
- PostgreSQL has one OPEN incident per target + type; repeated observations update
  it; after recovery a new episode gets a new row.
- Only signal type, timestamps and aggregated counts are recorded. No SQL source,
  user data, passwords or execution-plan XML is stored.

### API and UI
`GET /api/v1/targets/local-mssql/incidents?limit=30` (1..100) returns
OPEN items first, then recent resolved history. React: **Incidents** navigation,
then **Incident History** under Overview. CORS remains local-only via Vite proxy.

### Local validation
1. Build using the development branch:
   `git fetch origin && git switch feature/incident-detection-v0.3`
   (if it is not local, use `git switch --track origin/feature/incident-detection-v0.3`).
2. `docker compose up -d --build --force-recreate backend`.
3. Verify `GET http://127.0.0.1:18081/api/v1/targets` still shows ONLINE.
4. Verify `GET http://127.0.0.1:18081/api/v1/targets/local-mssql/incidents?limit=20`
   returns JSON (possibly empty).
5. First run `sql/05_query_store_demo_lab_only.sql`; then follow
   `sql/06_incident_blocking_demo_lab_only.sql` instructions in **two SSMS tabs**.
6. Inspect API and `http://127.0.0.1:5174/#incidents`.
   Event should become OPEN during blocking and RESOLVED after two healthy polls.
7. Run `docker compose run --rm backend` only for runtime inspection;
   Maven tests run in the build stage with `mvn test` (see backend folder).
   The usual Docker build currently uses `-DskipTests`, so successful
   image compilation alone does NOT confirm that JUnit tests were executed.

### Current limits and safety
This release does NOT capture a blocking session ID, wait chain, deadlock graph,
or validated root cause. A slow/blocked request may trigger multiple **different**
signals; these must not be conflated with separate proven outages. Very brief
events can be missed with 5s sampling.

No SQL KILL, forced transaction rollback, instance restart, index creation,
or automatic recovery SQL is sent to customer databases. Keep this local:
authentication, per-user DB isolation and production-safe monitoring permissions
are future requirements.
