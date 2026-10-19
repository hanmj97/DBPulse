# DBPulse v0.4.0-alpha — Blocking Chain Analyzer

## Product intent

Expand the incident detector from a simple blocking counter into session-level
diagnostics: who waits for whom, wait type, elapsed time, session status,
open transaction count, and a candidate terminal blocker.

This is a live observability product feature, not the final automated recovery engine.

## Data flow

MSSQL `sys.dm_exec_requests` LEFT JOIN `sys.dm_exec_sessions`
-> `MssqlBlockingChainCollector` (every ~5s)
-> `BlockingChainResolver`
-> PostgreSQL `blocking_chain_samples` + `blocking_chain_edges`
-> REST endpoints -> React `Blocking Chains`.

The independent v0.3 Incident History engine and v0.2 Query Store analyzer remain
intact. This pipeline does NOT issue INSERT/UPDATE/DELETE in customer SQL Server.

### Why join sys.dm_exec_sessions?

A head blocker may be sleeping while holding an open transaction, with no row in
`sys.dm_exec_requests`. We join its session metadata using
`r.blocking_session_id = s.session_id`, so UI can show `sleeping` and
`open_transaction_count` without retrieving user/host/login names.

### Interpretation limits

- A `rootCandidateSessionId` is the terminal node visible in the sampled subset,
  not proof of the root cause or that the session alone should be killed.
- Collects up to **100 positive blocking edges** for the configured database.
  Edges from other databases, negative blocker IDs and edges beyond the top 100
  are excluded. A missing edge can make an intermediate blocker appear terminal.
- Count of open transactions is not an assertion that a particular transaction
  is causing the wait. Wait type is **not** the exact granted lock mode.
- An empty ONLINE sample means no blocking edges were observed in that sampling
  instant; OFFLINE and STALE explicitly show no live edges.
- Incident evidence and completed SQL (Query Store) are different collections.
- No SQL text, parameter values, hostnames, usernames or execution-plan XML.
- The API is local-only; no customer-facing authentication / tenant isolation yet.

### Source DB permission

SQL Server 2022+ must grant `VIEW SERVER PERFORMANCE STATE` to the monitoring
login (server-level visibility). This was previously verified for the local
SQL Server 2025 instance. Do NOT grant `db_owner` or `sysadmin`.
Use dedicated, least-privilege monitoring credentials.

## Local validation

```powershell
# Execute inside D:\Projects\dbpulse-platform after saving any local changes
git fetch origin

# Only switch when git status --short is clean; preserves stable main
git switch --track origin/feature/blocking-chain-analyzer-v0.4

# Rebuild the Spring Boot backend (PostgreSQL volume stays intact)
docker compose up -d --build --force-recreate backend

# Check new endpoint (expected ONLINE, edges empty when no blocking)
Invoke-RestMethod "http://127.0.0.1:18081/api/v1/targets/local-mssql/blocking-chains" | ConvertTo-Json -Depth 8

# Recent positive observations, max 50 records
Invoke-RestMethod "http://127.0.0.1:18081/api/v1/targets/local-mssql/blocking-chains/history?limit=10" | ConvertTo-Json -Depth 8
```

Run tests in backend via Maven `mvn test` (the Docker build currently uses
`-DskipTests`, so a successful Docker build does not execute the tests).

### Reproduce a blocked session

Use the existing `sql/06_incident_blocking_demo_lab_only.sql` in **DBPulseLab**
with two privileged test sessions, not the read-only monitor user.
Session A holds an X lock while B waits. During B's wait, the UI/API should
show a relationship like: `blocker=71, waiter=80, waitType=LCK_M_X`.
Actual session IDs vary. After transactions end, current edges should be empty
but `history` should still retain sampled past positive edges.

### Next phases

- Correlate individual incident IDs with blocking samples and add time-range views.
- Extended Events deadlock-graph processing and wait/resource diagnostics.
- Explicitly approved safe recovery workflow and audit trails.
- Customer onboarding, tenant isolation, encrypted credentials and permissions.
