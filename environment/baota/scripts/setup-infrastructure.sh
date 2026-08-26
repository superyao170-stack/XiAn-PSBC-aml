#!/usr/bin/env bash
set -euo pipefail

BASE="${BANKGRAPH_BASE:-/www/wwwroot/bankgraph}"
ENV_FILE="${BANKGRAPH_ENV_FILE:-$BASE/environment/config/.env.production}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${BANKGRAPH_COMPOSE_FILE:-$SCRIPT_DIR/../docker-compose-infra.yml}"
TARGET="${1:-all}"

fail() { echo "[失败] $*" >&2; exit 1; }
info() { echo "[信息] $*"; }

[ "$(id -u)" = "0" ] || fail "请在宝塔终端中以 root 执行"
command -v docker >/dev/null 2>&1 || fail "未安装 Docker"
docker compose version >/dev/null 2>&1 || fail "未安装 docker compose 插件"
[ -f "$ENV_FILE" ] || fail "生产配置不存在：$ENV_FILE"
[ -f "$COMPOSE_FILE" ] || fail "基础设施编排文件不存在：$COMPOSE_FILE"

case "$TARGET" in
  all|kafka|redis|minio) ;;
  *) fail "用法：$0 [all|kafka|redis|minio]" ;;
esac

set -a
source "$ENV_FILE"
set +a

port_open() {
  timeout 2 bash -c "</dev/tcp/127.0.0.1/$1" >/dev/null 2>&1
}

wait_port() {
  local port="$1"
  local name="$2"
  for _ in $(seq 1 30); do
    if port_open "$port"; then
      info "$name 已就绪：127.0.0.1:$port"
      return 0
    fi
    sleep 1
  done
  fail "$name 启动后 30 秒仍未监听端口 $port"
}

wait_kafka_broker() {
  for _ in $(seq 1 30); do
    if docker exec bankgraph-kafka /opt/kafka/bin/kafka-topics.sh \
      --bootstrap-server 127.0.0.1:9092 --list >/dev/null 2>&1; then
      info "Kafka Broker 管理接口已就绪"
      return 0
    fi
    sleep 1
  done
  docker logs --tail 100 bankgraph-kafka >&2 || true
  fail "Kafka端口已打开，但Broker在30秒内未完成初始化"
}

start_service() {
  local service="$1"
  local container="$2"
  local port="$3"
  local label="$4"

  if port_open "$port"; then
    info "$label 已存在并监听 $port，保留现有服务"
    return 0
  fi

  if docker container inspect "$container" >/dev/null 2>&1; then
    info "启动已有容器：$container"
    docker start "$container" >/dev/null
  else
    info "创建并启动：$label"
    docker compose -p bankgraph-infra --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d "$service"
  fi
  wait_port "$port" "$label"
}

if [ "$TARGET" = "all" ] || [ "$TARGET" = "kafka" ]; then
  start_service kafka bankgraph-kafka 9092 "Apache Kafka 3.7.1"
  if docker container inspect bankgraph-kafka >/dev/null 2>&1; then
    wait_kafka_broker
    topic="${OUTBOX_TOPIC:-bankgraph.business.events}"
    docker exec bankgraph-kafka /opt/kafka/bin/kafka-topics.sh \
      --bootstrap-server 127.0.0.1:9092 \
      --create --if-not-exists \
      --topic "$topic" --partitions 3 --replication-factor 1 >/dev/null
    info "Kafka Topic 已确认：$topic"
  fi
fi

if [ "$TARGET" = "all" ] || [ "$TARGET" = "redis" ]; then
  [ -n "${REDIS_PASSWORD:-}" ] || fail "REDIS_PASSWORD 不能为空"
  [ "${REDIS_PASSWORD:-}" != "CHANGE_ME" ] || fail "请先设置真实 REDIS_PASSWORD"
  start_service redis bankgraph-redis 6379 "Redis 7"
fi

if [ "$TARGET" = "all" ] || [ "$TARGET" = "minio" ]; then
  [ -n "${MINIO_ACCESS_KEY:-}" ] || fail "MINIO_ACCESS_KEY 不能为空"
  [ -n "${MINIO_SECRET_KEY:-}" ] || fail "MINIO_SECRET_KEY 不能为空"
  [ "${MINIO_ACCESS_KEY:-}" != "CHANGE_ME" ] || fail "请先设置真实 MINIO_ACCESS_KEY"
  [ "${MINIO_SECRET_KEY:-}" != "CHANGE_ME" ] || fail "请先设置真实 MINIO_SECRET_KEY"
  start_service minio bankgraph-minio 9000 "MinIO"
fi

echo
echo "[完成] 基础设施检查/启动完成：$TARGET"
echo "Kafka、Redis、MinIO只监听127.0.0.1，不要在阿里云安全组开放其端口。"
