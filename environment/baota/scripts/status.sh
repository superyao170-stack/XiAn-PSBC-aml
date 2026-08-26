#!/usr/bin/env bash
set -euo pipefail

echo "Baota manages the Java process. This script only checks ports and HTTP responses."
ss -lnt | grep -E ':(3030|8030|5432|6379|7070|7687|9092|18082)[[:space:]]' || true
echo
curl -fsS -o /dev/null -w 'frontend HTTP %{http_code}\n' http://127.0.0.1:8030/ || true
curl -fsS -o /dev/null -w 'backend login HTTP %{http_code}\n' \
  -H 'Content-Type: application/json' -d '{"username":"sadmin","password":"invalid-probe"}' \
  http://127.0.0.1:3030/api/v1/auth/login || true
curl -fsS -o /dev/null -w 'risk analytics HTTP %{http_code}\n' http://127.0.0.1:18082/health || true
