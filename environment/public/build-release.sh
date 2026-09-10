#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "$0")/../.." && pwd)"
release_dir="$(mktemp -d)"
trap 'rm -rf "$release_dir"' EXIT

cd "$project_root"
if [[ "$(uname -s)" == "Darwin" ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
mvn -f backend/pom.xml clean package
npm --prefix frontend ci
npm --prefix frontend run build

mkdir -p "$release_dir/bankgraph/backend" "$release_dir/bankgraph/frontend" "$release_dir/bankgraph/environment"
cp backend/target/bankgraph-backend-*.jar "$release_dir/bankgraph/backend/app.jar"
cp -R frontend/dist "$release_dir/bankgraph/frontend/dist"
cp -R workers "$release_dir/bankgraph/workers"
cp -R environment/baota "$release_dir/bankgraph/environment/baota"
cp -R environment/public "$release_dir/bankgraph/environment/public"

mkdir -p artifacts
tar -C "$release_dir" -czf artifacts/bankgraph-public-release.tar.gz bankgraph
echo "Release created: $project_root/artifacts/bankgraph-public-release.tar.gz"
