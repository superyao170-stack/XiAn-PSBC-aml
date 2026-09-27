#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$PROJECT_ROOT"

if [[ "$(uname -s)" == "Darwin" && -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
elif [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_BIN="$(command -v java || true)"
  if [[ -z "$JAVA_BIN" ]]; then
    echo "未找到 Java 25，请先安装 JDK 25。" >&2
    exit 1
  fi
  export JAVA_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
fi

JAVA_VERSION="$($JAVA_HOME/bin/java -version 2>&1 | head -n 1)"
if [[ ! "$JAVA_VERSION" =~ \"25([.\"]|$) ]]; then
  echo "当前不是 Java 25：$JAVA_VERSION" >&2
  echo "请设置 JAVA_HOME 为 JDK 25 后重试。" >&2
  exit 1
fi

WORKER_PYTHON="$PROJECT_ROOT/workers/.venv/bin/python"
if [[ ! -x "$WORKER_PYTHON" ]]; then
  echo "AML Worker 共享虚拟环境不存在：$WORKER_PYTHON" >&2
  echo "请先执行：" >&2
  echo "  bash tools/setup-worker-environment.sh" >&2
  exit 1
fi

export WORKER_PYTHON
echo "Java: $JAVA_VERSION"
echo "AML Worker shared Python: $WORKER_PYTHON"
exec mvn -f backend/pom.xml spring-boot:run "$@"
