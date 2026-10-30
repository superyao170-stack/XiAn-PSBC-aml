#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$PROJECT_ROOT"

if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == "Darwin" && -x /usr/libexec/java_home ]]; then
  resolved_java_home="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  if [[ -z "$resolved_java_home" && -x "$HOME/.local/jdks/temurin-17.jdk/Contents/Home/bin/java" ]]; then
    resolved_java_home="$HOME/.local/jdks/temurin-17.jdk/Contents/Home"
  fi
  export JAVA_HOME="$resolved_java_home"
elif [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_BIN="$(command -v java || true)"
  if [[ -z "$JAVA_BIN" ]]; then
    echo "未找到 Java 17，请先安装 JDK 17。" >&2
    exit 1
  fi
  export JAVA_HOME="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
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

WORKER_PYTHON="$PROJECT_ROOT/workers/.venv/bin/python"
if [[ ! -x "$WORKER_PYTHON" ]]; then
  echo "AML Worker 共享虚拟环境不存在：$WORKER_PYTHON" >&2
  echo "请先执行：" >&2
  echo "  bash tools/setup-worker-environment.sh" >&2
  exit 1
fi

export WORKER_PYTHON
export SERVER_PORT="${SERVER_PORT:-3032}"
echo "Java: $JAVA_VERSION"
echo "AML Worker shared Python: $WORKER_PYTHON"
echo "XiAn-PSBC-aml backend: http://localhost:$SERVER_PORT"
exec mvn -f backend/pom.xml spring-boot:run "$@"
