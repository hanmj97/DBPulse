# DBPulse v0.5.0-alpha — Deadlock Detection & Analysis

## Product goal
Capture deadlocks that SQL Server has already detected, without relying on
5-second snapshots. The default `system_health` Extended Events session typically
collects `xml_deadlock_report`. DBPulse reads the `event_file` XEL target every
15 seconds and records only sanitized summaries.

## Privacy and database isolation
The source is server-level, but the query *only returns participants*
with `process/@currentdb = DB_ID()` of the configured monitored database.
It never returns or stores raw XML, SQL input buffers, query text, parameter
values, usernames, hostnames, resource object names or XEL file paths.
An event is identified across scans by SHA-256(file_name + NUL + file_offset).
This key is stored instead of the path.

**Limitation:** `currentdb` describes the participant's session current database.
Cross-database deadlocks can involve resources in other databases and thus be
underreported or only partially captured. When the victim belongs to another
database, its SPID is intentionally not returned. Some deadlock XML variants
might have missing attributes. This local alpha is not a full graph analyzer.

## Schema
`deadlock_events`: unique (target_id,event_key), timestamp, per-target
participating session count, nullable victim session ID.
`deadlock_participants`: session id and victim boolean only.
History retention: 14 days, with cascading deletion.

## API
- `GET /api/v1/targets/local-mssql/deadlocks/status`: ONLINE/OFFLINE/NOT_CONFIGURED.
- `GET /api/v1/targets/local-mssql/deadlocks?limit=20`: recent 1–50 summaries.

A healthy ONLINE state with an empty list means no matching deadlock was observed
in the accessible file window; it does **not** prove the service never deadlocked.
Source poll reads up to 100 recent events within the last hour; very busy
instances or file rollover can cause omissions. Event file writes can be delayed
and the file target may be absent/disabled. Re-scans are idempotent by unique key.

## Privileges
For SQL Server 2022+/2025, the monitoring account needs
`VIEW SERVER PERFORMANCE STATE` for `sys.fn_xe_file_target_read_file`.
SQL Server 2019- requires `VIEW SERVER STATE`. Do not grant
sysadmin, db_owner or unnecessary DML rights.

Official reference:
https://learn.microsoft.com/sql/relational-databases/system-functions/sys-fn-xe-file-target-read-file-transact-sql

## Local verification (DBPulseLab only)
1. `git status --short`; preserve any local changes.
2. `git fetch origin` then switch to `feature/deadlock-events-v0.5`.
3. `docker compose up -d --build --force-recreate backend`.
4. Check the new /deadlocks/status endpoint is ONLINE and /deadlocks API
   returns a JSON array. Check the old /targets, /incidents,
   /blocking-chains, and /query-performance endpoints remain healthy.
5. Before executing any deadlock scenario, use the accompanying
   `sql/07_deadlock_demo_lab_only.sql` and its two-session instructions.
   Tests may legitimately trigger SQL Server error 1205 (deadlock victim).
6. Wait up to ~60 seconds for XEL event-file flushing and two polls.
   Check API and local React `http://127.0.0.1:5174/#deadlocks`.
7. Re-query: event count must not increase for the same source event.
8. GitHub CI runs Maven unit tests and React build; local MSSQL/XEL
   end-to-end behavior must be verified separately before merge.

## Safety
- Read-only collector never creates, alters, ends or rolls back customer
  transactions. SQL Server chooses victims itself.
- The **separate** demo SQL modifies only dedicated DBPulseLab test tables.
- No automatic DB restart, KILL command, deadlock prevention claim, or
  index changes.
- Public use needs tenant authorization, credential isolation, auditing,
  and a more robust per-target event ingestion architecture.
