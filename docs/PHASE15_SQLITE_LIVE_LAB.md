# DBPulse Phase 15 — SQLite live collector lab (explicit opt-in)

## Scope and safety

- Complete all prior SQLite timestamp READ and WRITE compatibility patches first.
- Only a copied SQLite staging database is used. The previous PostgreSQL DB and backup remain untouched.
- This opt-in lab is isolated on `127.0.0.1:18082`. Close original DBPulse on 18081 and stop previous SQLite preview before beginning, so duplicate polling cannot occur.
- Java runner never enables DPAPI: enter the SQL Server login **only in the local browser form**. Never save the password to source or PowerShell history.
- Use your own `DBPulseLab` SQL Server Developer environment and the existing least-privileged `dbpulse_monitor` login. Do not point the lab at customer/production databases.
- Only for a local self-signed certificate, explicitly launch with `-AllowLocalSelfSignedLab`. Otherwise omit the flag and keep certificate validation enabled.
- No database reset, migration, deletion, docker down, or volume deletion is performed.

## Install

Unpack the ZIP at project root. Run Python patch tests and `apply_sqlite_lab_origin.py` (dry-run then `--apply`) and build a **new** Windows app image. This is necessary so same-origin POSTs from port 18082 are permitted. Use the project's existing `tools/build_dbpulse_windows_app_image.ps1` followed by backend Java tests (`docker run --rm --entrypoint mvn dbpulse-windows-image-builder -B test`).

## Live procedure (2 PowerShell windows)

1. Stop 18081/18082 DBPulse servers. Start `tools/run_dbpulse_sqlite_live_lab.ps1 -AllowLocalSelfSignedLab` (only your own local Developer instance) in PowerShell A. Observe `PREVIEW_FILE:` and `LIVE_SQLITE_LAB_READY_FOR_EXPLICIT_UI_START` and Spring `Started`.
2. Open PowerShell B, navigate to the project, select the exact `PREVIEW_FILE` path printed by A: `$labFile = 'C:\...\dbpulse-preview-...sqlite'`. Store a new baseline path in the user's LOCALAPPDATA. Run `py -3 .\tools\sqlite_live_audit.py capture --file "$labFile" --baseline "$baseline"`. Expect `SQLITE_LIVE_BASELINE_CAPTURED`.
3. Open `http://127.0.0.1:18082/#targets`. Choose the existing local DB or enter: own localhost, verified TCP port, `DBPulseLab`, least-privilege monitoring account, password (typed in browser form), optional self-signed dev setting. Click **선택한 DB 모니터링 시작**. Do not click credentials saving (disabled).
4. Verify collector becomes `ONLINE` and let the monitor run for 60–90 seconds. Do not start 18081 backend in parallel.
5. While it is `ONLINE`, run `tools/check_dbpulse_sqlite_live_api.ps1` in PowerShell B. `BLOCKING` and `WAIT` **history detail** may be empty if no event occurred; collection still emits empty header samples.
6. In the browser click **현재 DB 연결 해제**, then in PowerShell A use Ctrl+C to stop Java. Keep the SQLite preview file and its `-wal/-shm` sidecars intact, if any.
7. With the backend fully stopped, run `py -3 .\tools\sqlite_live_audit.py verify --file "$labFile" --baseline "$baseline"` in PowerShell B. Expect `SQLITE_LIVE_COLLECTOR_INSERTS_AND_FK_VERIFIED`.

All queries in the Python audit are read-only. The audit needs only 3 append-only header tables: `metric_snapshots`, `blocking_chain_samples`, `wait_samples`. Query Store and Deadlock may have no new rows without a triggering workload. The audit never prints DB login, SQL query text, or encrypted credential bytes.

## Remaining after a successful lab

- End-to-end retention, failover, WAL/atomic backup/restore and rollback checks on SQLite, long-running observation overhead and permissions, memory and startup limits.
- Explicit user-controlled desktop backend switch to SQLite after backup verification; keep PostgreSQL available for rollback during test cycle.
- Actual Windows native app window (WebView2), installer (MSIX/NSIS/WiX), desktop shortcut, signing, uninstall and upgrade; optional database-level CPU-time metrics (not inaccurate DB CPU%).
