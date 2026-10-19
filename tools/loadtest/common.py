"""Connection config shared by the local DBPulse lab tools."""
import os

DEFAULT_CONNECTION_STRING = (
    "DRIVER={ODBC Driver 18 for SQL Server};"
    "SERVER=localhost;DATABASE=DBPulseLab;"
    "Trusted_Connection=yes;Encrypt=yes;TrustServerCertificate=yes;"
    "APP=DBPulseLab;"
)


def connection_string():
    value = os.getenv("DBPULSE_CONN_STR", DEFAULT_CONNECTION_STRING)
    # Only permit local Windows-auth connections to the dedicated test database.
    pairs = {}
    for fragment in value.split(";"):
        if not fragment.strip():
            continue
        if "=" not in fragment:
            raise ValueError("Invalid connection string field")
        key, item = fragment.split("=", 1)
        key = key.strip().lower()
        if key in pairs:
            raise ValueError(f"Duplicate connection string field: {key}")
        pairs[key] = item.strip()
    database = [pairs[key] for key in ("database", "initial catalog") if key in pairs]
    if len(database) != 1 or database[0].lower() != "dbpulselab":
        raise ValueError("Safety check: connection string must explicitly select DATABASE=DBPulseLab.")
    server = pairs.get("server", "").lower()
    local_prefixes = ("localhost", "127.0.0.1", ".", "(local)")
    if not any(server == prefix or server.startswith(prefix + "\\") or
               server.startswith(prefix + ",") for prefix in local_prefixes):
        raise ValueError("Safety check: only localhost SQL Server instances are permitted.")
    if pairs.get("trusted_connection", "").lower() not in ("yes", "true"):
        raise ValueError("Use Windows Trusted_Connection=yes for this local starter kit.")
    return value
