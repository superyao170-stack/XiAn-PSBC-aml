#!/usr/bin/env bash
set -euo pipefail

BASE=/www/wwwroot/bankgraph
ENV_FILE="$BASE/environment/config/.env.production"
JAVA=/www/server/java/jdk-17.0.8/bin/java

[ "$(id -u)" = "0" ] || { echo "Run this script as root in the Baota terminal" >&2; exit 1; }
[ -x "$JAVA" ] || { echo "Java 17 not found: $JAVA" >&2; exit 1; }
[ -f "$BASE/backend/app.jar" ] || { echo "Missing backend/app.jar" >&2; exit 1; }
[ -f "$BASE/frontend/dist/index.html" ] || { echo "Missing frontend/dist/index.html" >&2; exit 1; }
[ -f "$ENV_FILE" ] || { echo "Missing environment/config/.env.production" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "Docker is required for PostgreSQL 16" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Docker Compose v2 is required" >&2; exit 1; }

mkdir -p "$BASE/backend/logs" "$BASE/workers/aml-intelligence"
mkdir -p "$BASE/workers/structured-case-identification/runs" "$BASE/workers/structured-case-identification/uploads"
chmod +x "$BASE/environment/baota/scripts/"*.sh

if ! id www >/dev/null 2>&1; then
  echo "Baota user 'www' does not exist" >&2
  exit 1
fi

python3 -m venv "$BASE/workers/.venv"
"$BASE/workers/.venv/bin/pip" install --upgrade pip
"$BASE/workers/.venv/bin/pip" install -r "$BASE/workers/requirements.txt"

set -a
source "$ENV_FILE"
set +a
docker compose --env-file "$ENV_FILE" -f "$BASE/environment/baota/docker-compose-infra.yml" up -d postgres
for _ in $(seq 1 30); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' bankgraph-postgres 2>/dev/null || true)" = "healthy" ] && break
  sleep 2
done
[ "$(docker inspect -f '{{.State.Health.Status}}' bankgraph-postgres 2>/dev/null || true)" = "healthy" ] \
  || { echo "PostgreSQL 16 Docker container did not become healthy" >&2; exit 1; }
"$BASE/environment/baota/scripts/init-database.sh"
"$BASE/workers/.venv/bin/python3" "$BASE/environment/baota/scripts/init_tugraph.py"

# Python workers read their own .env files. Keep them synchronized with the
# authoritative production configuration used by the Java process.
install -m 0600 -o www -g www "$ENV_FILE" "$BASE/workers/aml-intelligence/.env"
install -m 0600 -o www -g www "$ENV_FILE" "$BASE/workers/structured-case-identification/.env"

chown -R www:www "$BASE/backend" "$BASE/frontend" "$BASE/workers"
chown root:www "$ENV_FILE"
chmod 640 "$ENV_FILE"

# The backend is managed exclusively by the Baota Java Project manager.
# Remove the legacy systemd unit, if an earlier deployment installed it, to
# prevent two supervisors from competing for port 3030.
if systemctl list-unit-files bankgraph-backend.service >/dev/null 2>&1; then
  systemctl disable --now bankgraph-backend.service || true
  rm -f /etc/systemd/system/bankgraph-backend.service
  systemctl daemon-reload
fi

echo "Initialization completed. Add the backend in Baota -> Java Project."
echo "Startup command:"
echo "$JAVA -Xmx2048M -Xms512M -jar $BASE/backend/app.jar --server.port=3030 --spring.config.import=optional:file:$ENV_FILE[.properties]"
echo "Port: 3030; run user: www; project root: $BASE/backend"
echo "Configure the Baota Nginx site with:"
echo "$BASE/environment/baota/nginx-bankgraph.conf"
echo "After starting the Java project, run: $BASE/environment/baota/scripts/status.sh"
