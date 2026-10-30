#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/../.." && pwd)"
release_dir="$(mktemp -d)"
trap 'rm -rf "$release_dir"' EXIT

cd "$project_root"
if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == "Darwin" ]]; then
  resolved_java_home=""
  if [[ -x /usr/libexec/java_home ]]; then
    resolved_java_home="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  fi
  if [[ -z "$resolved_java_home" && -x "$HOME/.local/jdks/temurin-17.jdk/Contents/Home/bin/java" ]]; then
    resolved_java_home="$HOME/.local/jdks/temurin-17.jdk/Contents/Home"
  fi
  if [[ -n "$resolved_java_home" ]]; then
    export JAVA_HOME="$resolved_java_home"
  fi
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "未找到 Java 17，请设置 JAVA_HOME 为 JDK 17 后重试。" >&2
  exit 1
fi
JAVA_VERSION="$("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1)"
if [[ ! "$JAVA_VERSION" =~ \"17([.\"]|$) ]]; then
  echo "当前不是 Java 17：$JAVA_VERSION" >&2
  echo "请设置 JAVA_HOME 为 JDK 17 后重试。" >&2
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"
mvn -f backend/pom.xml clean package
npm --prefix frontend ci
npm --prefix frontend run build

mkdir -p "$release_dir/bankgraph/backend" "$release_dir/bankgraph/frontend" "$release_dir/bankgraph/environment"
cp backend/target/bankgraph-backend-*.jar "$release_dir/bankgraph/backend/app.jar"
cp -R frontend/dist "$release_dir/bankgraph/frontend/dist"
mkdir -p "$release_dir/bankgraph/workers"
tar -C workers \
  --exclude='./.venv' \
  --exclude='*/.venv' \
  --exclude='./.env' \
  --exclude='*/.env' \
  --exclude='./.env_*' \
  --exclude='*/.env_*' \
  --exclude='*/__pycache__' \
  --exclude='*/.pytest_cache' \
  --exclude='*/uploads' \
  --exclude='*/runs' \
  --exclude='.DS_Store' \
  -cf - . | tar -C "$release_dir/bankgraph/workers" -xf -
cp -R environment/baota "$release_dir/bankgraph/environment/baota"
cp -R environment/public "$release_dir/bankgraph/environment/public"

mkdir -p artifacts
tar -C "$release_dir" -czf artifacts/bankgraph-public-release.tar.gz bankgraph
echo "Release created: $project_root/artifacts/bankgraph-public-release.tar.gz"
