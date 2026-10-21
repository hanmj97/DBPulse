# Phase 14 — Windows user DPAPI credential store (native host dev mode)

**Status:** Feature implemented in a Draft development branch. Java + React CI tests run
on Linux and verify feature gating / API behavior; they **cannot** exercise native
Windows DPAPI. Do not mark actual Windows encryption/restart tests PASS until the
user runs them locally.

## Why there are two modes

- **Current Docker backend (Linux JVM):** credential vault capability is
  \`available=false\`; DBPulse still asks for the SQL login password every time.
  No OS credential secret is saved, no plaintext fallback, and existing Docker
  monitoring continues unchanged.
- **Optional native Windows backend (Windows JVM, signed-in user):** when explicitly
  launched with \`DBPULSE_WINDOWS_DPAPI_ENABLED=true\`, store is
  \`%LOCALAPPDATA%\DBPulse\credentials\<uuid>.dpapi\`. Only protected
  DPAPI blobs are on disk. Decryption needs the same Windows user profile.
  Moving the file to another Windows account/PC will not make it decryptable.
  The saved secret never appears in a GET API, query-string or React storage.

Java implementation uses JNA Crypt32Util \`cryptProtectData\` and
\`cryptUnprotectData\`, user scope (no LOCAL_MACHINE option).
The byte-array containing the unprotected password is cleared after use;
Java/JDBC Strings may remain transiently on the JVM heap while a connection
is active. This is not a secure enclave. Protect Windows user access,
antivirus/OS updates and local access to \`localhost\` APIs.

## How to try Windows-native mode safely

**Prerequisites:** Windows 10/11, Docker Desktop with the **existing**
PostgreSQL volume intact, Java **21** installed, npm already installed for
React. Maven 3.9+ is optional: if missing, the development script builds the
backend jar in a temporary Docker Maven builder container and copies it out.

\`\`\`powershell
# Move to DBPulse source checkout.
Set-Location D:\Projects\dbpulse-platform

# Ensure the feature branch is checked out, and inspect local edits first.
git branch --show-current
git status --short
git pull --ff-only origin feature/target-onboarding-v0.14

# Optional read of settings; do NOT print passwords or upload .env.
notepad .env

# Start native Windows backend. Script builds its JAR, stops Docker backend
# only (frees 127.0.0.1:18081), keeps Docker PostgreSQL, then runs Java in
# foreground as the signed-in Windows user. Ctrl+C stops only native backend.
.\tools\run_dbpulse_windows_host_lab.ps1
\`\`\`

Open a **second** PowerShell:

\`\`\`powershell
# Use the existing React development frontend against native Spring Boot.
Set-Location D:\Projects\dbpulse-platform\frontend
npm.cmd run dev
\`\`\`

Browse \`http://127.0.0.1:5174/#targets\`.

1. Verify \`Windows 사용자 계정 DPAPI 보호 활성화\` is visible. If still
   unavailable, ensure you are executing **native Java on Windows**, not Docker.
2. Select/enter a permitted test SQL Server DB and correct login/password.
   On native Windows, \`127.0.0.1\` is the Windows SQL Server host; a previously
   saved \`host.docker.internal\` registry address may need a new registration.
3. If using a self-signed *local developer* SQL Server, keep the explicit
   backend \`DBPULSE_LOCAL_TRUST_SELF_SIGNED=true\` and choose that exception
   under Advanced Settings. Production/remote SQL Server should use an
   approved certificate without this exception.
4. Start monitoring, then wait for actual **ONLINE** (successful DMV poll).
5. Click \`이 PC에 암호화 저장\`, accept the explicit opt-in confirmation.
   The row should indicate an encrypted password is available. Verify browser
   JSON contains \`savedCredential: true\`, **never the password**.
6. Disconnect. Click the existing registered DB card and \`저장된 연결\`
   to connect again **without retyping** the SQL login password. The
   backend still revalidates the host allow-list/TLS and performs the
   read-only SQL connection probe.
7. Stop native Java with Ctrl+C. Launch the Windows backend script again.
   It should retain the encrypted file and offer \`저장된 연결\`. It does
   **not** make unapproved background database connections automatically.
8. Click \`암호 해제\` to remove only the saved encrypted credential.
   Unregistering a DB also removes its encrypted credential first. DBPulse
   history and the real SQL Server DB remain untouched.
9. Verify on Docker backend that \`credential-capability.available=false\`;
   no saved-credential control may appear.

Never commit or send \`.env\`, \`%LOCALAPPDATA%\DBPulse\credentials\`,
database logins/passwords, raw SQL text or XE files.

## API contracts

- \`GET /api/v1/local-targets/credential-capability\` →
  \`{available, provider}\`. No secrets.
- \`GET /api/v1/local-targets\` → normal metadata plus
  \`savedCredential: boolean\`. Does not expose encrypted bytes.
- \`POST /api/v1/local-targets/{id}/remember-credential\` → saves the
  current **already-verified active** UI target's in-memory password;
  disabled unless native Windows user DPAPI is available.
- \`POST /api/v1/local-targets/{id}/start-saved\` with
  \`{trustLocalCertificate: false}\` → loads protected secret on backend,
  performs normal connection test, and starts the chosen registered UUID.
- \`POST /api/v1/local-targets/{id}/forget-credential\` → removes the
  saved blob; active session is unaffected until disconnected.
- Unregister deletes associated encrypted secret and registry row,
  **never historical metrics or remote SQL Server data**.

All POSTs require the same local Origin validation, JSON and
\`X-DBPulse-Local-Request: 1\`. This is a local-only app, not a
multiuser/Internet-exposed authentication service.

## Not yet implemented

- Running frontend **from the same packaged local server** without
  the Vite development server.
- Installer \`.exe\`, bundled Java runtime and embedded/local metadata DB
  management. The initial Windows-host mode **still uses Docker Postgres**.
- Background unattended automatic reconnection at app launch (intentionally
  not enabled until Windows-native installer/launch lifecycle and explicit
  user preference can be safely verified).
- Windows-native DPAPI integration test on the user's PC and safe migration
  from Docker to packaged host. Do not switch off TLS validation globally.
