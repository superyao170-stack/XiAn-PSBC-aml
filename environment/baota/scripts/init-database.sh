#!/usr/bin/env bash
set -euo pipefail

BASE=/www/wwwroot/bankgraph
ENV_FILE="$BASE/environment/config/.env.production"
set -a
source "$ENV_FILE"
set +a

for value in "$DB_HOST" "$DB_PORT" "$DB_NAME" "$DB_USERNAME" "$DB_PASSWORD"; do
  [ -n "$value" ] || { echo "Database configuration is incomplete" >&2; exit 1; }
done
[[ "$DB_NAME" =~ ^[A-Za-z0-9_]+$ ]] || { echo "Invalid DB_NAME" >&2; exit 1; }

export PGPASSWORD="$DB_PASSWORD"
connection_error=$(mktemp)
trap 'rm -f "$connection_error"' EXIT
if ! exists=$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d postgres -tAc \
  "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" 2>"$connection_error" | tr -d '[:space:]'); then
  echo "PostgreSQL login failed for DB_USERNAME=$DB_USERNAME." >&2
  cat "$connection_error" >&2
  echo "Verify the actual PostgreSQL role with: sudo -u postgres psql -tAc '\\du'" >&2
  echo "For a default PostgreSQL installation, DB_USERNAME is normally postgres, not postgresql." >&2
  exit 1
fi
if [ "$exists" != "1" ]; then
  psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USERNAME" -d postgres -v ON_ERROR_STOP=1 \
    -c "CREATE DATABASE \"$DB_NAME\" WITH ENCODING 'UTF8' TEMPLATE template0"
  echo "Created PostgreSQL database: $DB_NAME"
else
  echo "PostgreSQL database already exists: $DB_NAME"
fi
