# DBPulse v0.21 — Release Readiness (PREVIEW, NOT RELEASED)

Status: 2026-10-22, feature freeze and release hardening.
Do not add feature modules or merge stacked Draft PRs until the real
Windows deployment and SQL Server lab acceptance have completed.

## Verified evidence, not production claims

- Windows WebView2 native app opens and X-close releases port 18081 in user's lab.
- Java Query Store, SQLite persistence and DPAPI behaviors have focused unit tests.
- Windows Actions builds an unsigned per-user Inno Setup package, exercises
  install/self-test/uninstall and verifies a data sentinel is preserved.
- Real Windows lab previously showed Query Store query history/SQL text on-click,
  selected DB Query Store CPU time, incidents, deadlocks and wait diagnostics.
- No 24-hour soak, first-run fresh-machine onboarding, executable signing or
  broad untrusted-PC security audit has been completed.

## New safety gates

- tools/dbpulse_release_preflight.py: read-only installed app-image, local
  SQLite opt-in marker, 10 metadata tables, full integrity and FK checks.
  Run with the app stopped. Only error codes and numeric totals are printed;
  no DB records, target names, paths, SQL texts or passwords.
- tools/dbpulse_local_soak_probe.py: foreground GET-only local health/target
  check every 15–300 seconds. No SQL workload; not proof of low observer cost.
- New CI tests for safe preflight and no-network soak logic.
- Windows installer test preserves unique CI sentinels in data, desktop,
  credentials, backups and logs. It removes ONLY its own sentinel files.
- CI provides SHA256SUMS.txt for the Windows unsigned Setup EXE. The checksum
  detects accidental corruption but is NOT a publisher signature.

## Release blockers (do not check without observed evidence)

- [ ] Real installed Windows upgrade, start/stop/restart, no SQLite loss.
- [ ] Verify app local storage by read-only preflight before/after test.
- [ ] Backup + temporary restore/drill of actual existing SQLite user DB.
- [ ] 60-minute foreground API availability: zero failed health probes.
- [ ] 24-hour authorized lab soak: healthy collector status, SQLite integrity,
      retention rollover, process memory and CPU, no unexplained failures.
- [ ] SQL Server observer A/B comparison for query latency/CPU/reads/waits
      with matched workloads; prior synthetic lab did not establish causality.
- [ ] Query Store OFF, READ_ONLY, and missing permission graceful handling.
- [ ] Authorized lab deadlock, Blocking, Wait and Incident behavior; avoid
      real customer database changes or unsafe automatic remediation.
- [ ] SQL text local UI security/privacy audit; no private SQL or DPAPI in logs.
- [ ] WAL hard crash/low disk and recovery on actual Windows machine.
- [ ] Fresh-PC first-run metadata setup; currently requires pre-existing
      validated SQLite DB and opt-in marker, so installer alone is insufficient.
- [ ] WebView2 prerequisite/bootstrap, SmartScreen and signing policy.
- [ ] Choose repository license/redistribution terms for third-party release.
- [ ] Review stacked Draft PRs 12–19 and hardening PR in dependency order.
- [ ] Tag/promote only once all release-critical blockers are resolved.

## Windows acceptance procedure (authorized DBPulseLab only)

1. Exit DBPulse normally; confirm listener port 18081 is free.
2. From repository root: py -3 .\tools\dbpulse_sqlite_recovery.py check
3. Create a private, verified snapshot:
   py -3 .\tools\dbpulse_sqlite_recovery.py backup
   and verify the resulting .sqlite with the printed backup path:
   py -3 .\tools\dbpulse_sqlite_recovery.py verify-backup --backup "PATH_FROM_OUTPUT"
   NEVER upload that backup, SQL text, XEL or DPAPI files to GitHub/chat.
4. Read-only release preflight:
   py -3 .\tools\dbpulse_release_preflight.py
5. Start via Windows Start Menu, select registered DBPulseLab and explicitly
   start monitoring; check Overview, Blocking, Deadlock, Wait, Incident,
   Query Store latest & 7-day saved history, SQL Tuning Advisor and SQL drawer.
   The displayed per-DB CPU is a Query Store ONE-CORE-EQUIVALENT indicator,
   not exact SQL Server per-database realtime CPU utilization.
6. Leave app running, then run in foreground in another PowerShell window:
   py -3 .\tools\dbpulse_local_soak_probe.py --minutes 60 --interval-sec 60
   Zero failed HTTP samples proves local API liveness ONLY.
7. Close via X; verify port 18081 free, rerun read-only SQLite check,
   compare total rows sensibly (collector should normally add rows),
   verify preserved enrollment, then relaunch.
8. Stop release on failure. Preserve source SQLite and PostgreSQL volumes.
   Never use docker compose down --volumes.

This document records readiness and limitations; it does not publish any
official, signed, secure-for-external-users product release.

## Version metadata alignment (stabilization)

- Windows Inno Setup AppVersion 0.21.0 and UI footer v0.21.0-preview
  now match Java Maven project version 0.21.0, npm package version 0.21.0,
  .NET WinForms assembly/file version 0.21.0, and jpackage version 0.21.0.
- The unpacked Windows app-image GitHub artifact also uses a v0.21 label.
- Version alignment is packaging hygiene only, not proof of compatibility
  or production readiness. No new SQL Server or SQLite schema migration.
