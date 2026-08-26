#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose-stack.yml"
ARCH="$(uname -m)"
BUNDLE_FILE="${STACK_BUNDLE_FILE:-$PROJECT_ROOT/artifacts/datagraph-bank-stack-${ARCH}.tar}"

POSTGRES_IMAGE="${POSTGRES_IMAGE:-datagraph-bank/postgres:16-alpine}"
TUGRAPH_IMAGE="${TUGRAPH_IMAGE:-datagraph-bank/tugraph:4.5.2}"
CORENLP_IMAGE="${CORENLP_IMAGE:-datagraph-bank/corenlp:4.5.10}"
BGE_MODELS_IMAGE="${BGE_MODELS_IMAGE:-datagraph-bank/bge-models:e44369c}"
TUGRAPH_SOURCE="tugraph/tugraph-runtime-centos7@sha256:b1b0ecc39a580a7cbdac7b4b7ce35f6a92f0981254d314bc1c0a50ca71d4df0d"

export POSTGRES_IMAGE TUGRAPH_IMAGE CORENLP_IMAGE BGE_MODELS_IMAGE

usage() {
  echo "Usage: $0 {up|build|package|install|status|down} [bundle.tar]"
  echo "  up       Prepare all six components and start the stack"
  echo "  build    Build/tag all images without starting containers"
  echo "  package  Export a complete offline Docker image bundle"
  echo "  install  Load an offline bundle and start the stack"
  echo "  status   Show service and health status"
  echo "  down     Stop the stack without deleting data volumes"
}

require_docker() {
  command -v docker >/dev/null 2>&1 || { echo "Docker is not installed." >&2; exit 1; }
  docker info >/dev/null 2>&1 || { echo "Docker Desktop is not running." >&2; exit 1; }
}

image_exists() {
  docker image inspect "$1" >/dev/null 2>&1
}

prepare_base_images() {
  local version_output
  if ! image_exists "$POSTGRES_IMAGE"; then
    image_exists postgres:16-alpine || docker pull postgres:16-alpine
    version_output="$(docker run --rm postgres:16-alpine postgres --version)"
    grep -q 'PostgreSQL) 16\.' <<<"$version_output"
    docker tag postgres:16-alpine "$POSTGRES_IMAGE"
  fi
  if ! image_exists "$TUGRAPH_IMAGE"; then
    image_exists "$TUGRAPH_SOURCE" || docker pull --platform linux/amd64 "$TUGRAPH_SOURCE"
    version_output="$(docker run --rm --platform linux/amd64 "$TUGRAPH_SOURCE" lgraph_server --version 2>&1)"
    grep -q 'TuGraph v4\.5\.2' <<<"$version_output"
    docker tag "$TUGRAPH_SOURCE" "$TUGRAPH_IMAGE"
  fi
}

build_custom_images() {
  local services=()
  image_exists "$CORENLP_IMAGE" || services+=(corenlp)
  image_exists "$BGE_MODELS_IMAGE" || services+=(bge-models)
  if ((${#services[@]})); then
    docker compose -f "$COMPOSE_FILE" build "${services[@]}"
  fi
}

prepare_volumes() {
  local volume
  for volume in \
    "${POSTGRES_VOLUME:-bankgraph_postgres_data}" \
    "${TUGRAPH_VOLUME:-deploy_tugraph_data}" \
    "${TUGRAPH_LOG_VOLUME:-deploy_tugraph_logs}"; do
    docker volume inspect "$volume" >/dev/null 2>&1 || docker volume create "$volume" >/dev/null
  done
}

remove_legacy_containers() {
  local container project
  for container in bankgraph-postgres bankgraph-tugraph bankgraph-corenlp bankgraph-bge-m3 bankgraph-bge-models; do
    docker container inspect "$container" >/dev/null 2>&1 || continue
    project="$(docker inspect "$container" --format '{{index .Config.Labels "com.docker.compose.project"}}' 2>/dev/null || true)"
    if [[ "$project" != "bankgraph-stack" ]]; then
      echo "Replacing legacy container $container; named data volumes are preserved."
      docker rm --force "$container" >/dev/null
    fi
  done
}

start_stack() {
  prepare_volumes
  remove_legacy_containers
  docker compose -f "$COMPOSE_FILE" up -d --no-build
  docker compose -f "$COMPOSE_FILE" ps
}

require_docker
command="${1:-}"

case "$command" in
  up)
    prepare_base_images
    build_custom_images
    start_stack
    ;;
  build)
    prepare_base_images
    docker compose -f "$COMPOSE_FILE" build corenlp bge-models
    ;;
  package)
    prepare_base_images
    docker compose -f "$COMPOSE_FILE" build corenlp bge-models
    mkdir -p "$(dirname "$BUNDLE_FILE")"
    docker save --output "$BUNDLE_FILE" \
      "$POSTGRES_IMAGE" "$TUGRAPH_IMAGE" "$CORENLP_IMAGE" "$BGE_MODELS_IMAGE"
    echo "Offline bundle created: $BUNDLE_FILE"
    ;;
  install)
    bundle="${2:-$BUNDLE_FILE}"
    [[ -f "$bundle" ]] || { echo "Bundle not found: $bundle" >&2; exit 1; }
    docker load --input "$bundle"
    start_stack
    ;;
  status)
    docker compose -f "$COMPOSE_FILE" ps
    ;;
  down)
    docker compose -f "$COMPOSE_FILE" down
    ;;
  *)
    usage
    exit 1
    ;;
esac
