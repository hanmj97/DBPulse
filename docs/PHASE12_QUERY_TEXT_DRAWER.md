# DBPulse v0.12 — On-demand Query Store SQL Text Drawer

## User request
In the DBPULSE / 쿼리 page, allow a user to click **SQL 보기** and inspect
the corresponding Query Store SQL statement on a right-hand sliding panel.
The Query ID / Plan ID pair must be the one the user selected.

Both SQL Tuning Advisor candidate cards and the lower Query Store table
now have a button. No SQL is loaded on page load, during background polling,
or merely by expanding a plan card.

## Security decision (important)

Query Store SQL text can contain **sensitive literals, names, or tokens**.
The existing DBPulse prototype is LOCAL-ONLY without user authentication.
Therefore the feature is **disabled by default** until the operator explicitly
opts in with `DBPULSE_QUERY_TEXT_ENABLED=true` in their local `.env`.

Do not turn this on for a remotely exposed deployment or customer database
without authentication, per-target authorization, auditing, rate limits and
proper secret/PII handling.

- The backend validates the configured target and a *fresh* latest Query
  Store sample; selected Query ID / Plan ID must be in the collected
  bounded top-50 snapshot.
- After the button click, Spring JDBC performs a single parameterized,
  read-only SQL Server Query Store lookup. Results aren't stored in
  PostgreSQL, used by collectors, printed to logs, or saved to browser
  localStorage/sessionStorage.
- Server-side `SUBSTRING(query_sql_text, 1, 65537)` bounds the query text
  transferred to Java; Java caps displayed SQL at 65,536 UTF-16 code units,
  and reports `truncated=true` if longer.
- Only successful 200 responses contain captured SQL text, with
  `Cache-Control: no-store`, `Pragma: no-cache`, and `nosniff` headers.
  Browser DevTools/network tools can still inspect the response; **never
  screenshot or copy sensitive SQL into public places**.
- React displays text in `<pre><code>{queryText}</code></pre>`, not using
  HTML injection. The request is aborted on close or selection change;
  the response is cleared from component state when closed.
- Copying SQL is an *explicit* user action that writes to the system
  clipboard; the clipboard itself can be visible to other apps.
- No SQL execution, DDL, index modifications or plan forcing.

## Source limitations

The returned text is from SQL Server's
`sys.query_store_query_text.query_sql_text`, associated to the selected
`sys.query_store_query` and `sys.query_store_plan` rows.

It is **Query Store's stored statement**, not necessarily the exact batch
or concrete parameter values originally submitted. Auto parameterization,
different statement representations and Query Store capture policy affect
what can be shown. For statements >65,536 characters, the drawer explicitly
states that only a prefix was returned.

## API

`GET /api/v1/targets/local-mssql/query-performance/query-text/{queryId}/{planId}`

- 403: opt-in disabled (the default)
- 404: unrecognized target, or pair not in latest captured snapshot
- 503: Query Store is not online/fresh, or SQL Server access fails
- 200: `queryId, planId, status, queryText, originalCharacters, truncated`

`status` is `AVAILABLE` or `QUERY_TEXT_UNAVAILABLE`.

## Local verification

```powershell
# Review changes first and switch to the existing v0.12 feature branch
cd D:\Projects\dbpulse-platform
git status --short
git fetch origin
git switch feature/query-plan-comparison-v0.12
git pull --ff-only origin feature/query-plan-comparison-v0.12

# Edit the local .env file and add ONLY this explicit opt-in setting:
# DBPULSE_QUERY_TEXT_ENABLED=true
# Do not paste your MSSQL/PostgreSQL passwords or sensitive query text into chat.

# Rebuild the Spring Boot backend and pass the new environment flag.
# Existing PostgreSQL volume is NOT removed.
docker compose up -d --build --force-recreate backend
docker compose ps

# Check actual running version
Invoke-RestMethod "http://127.0.0.1:18081/api/v1/health"

# Confirm capture status and choose a REAL recent Query Store pair
$base = "http://127.0.0.1:18081/api/v1/targets/local-mssql"
$advisor = Invoke-RestMethod "$base/query-performance/advisor?limit=50&sort=recent"
$advisor.candidates | Select-Object -First 10 queryId,planId,lastExecutedAt

# Visit the React UI and click SQL 보기; avoid printing raw SQL into
# PowerShell history, logs, screenshots or chat messages.
```

Open `http://127.0.0.1:5174/#query-performance` and verify:
1. Click a Query/Plan card's **SQL 보기**. A drawer slides in from the
   right displaying the captured statement (or a clear unavailability state).
2. It works for both the Advisor and lower Query Store table.
3. Copy button works only on an explicit click. Backdrop, X and Escape close.
4. Navy/Midnight Dark colors remain consistent. SQL containing `<script>`
   shows text, not executable HTML.
5. No changes to latest-first sorting, search filters, plan comparison,
   on-demand operator evidence, incidents or waits.
6. Clear opt-in flag/restart backend: subsequent clicks report 403 and
   no SQL is transmitted.

## Review gate
GitHub CI success proves Java tests and React build, NOT actual SQL Server
permissions/Query Store text availability. Keep PR draft until DBPulseLab
local integration and security checks are complete.
