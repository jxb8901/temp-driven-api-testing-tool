#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

mvn -q -f "$repo_root/pom.xml" -pl att-server -am -DskipTests package

war_files=("$repo_root"/att-server/target/att-server-*.war)
if [[ ${#war_files[@]} -ne 1 || ! -f "${war_files[0]}" ]]; then
  echo "Expected exactly one packaged ATT Server WAR under att-server/target." >&2
  exit 1
fi

unpacked="$(mktemp -d)"
trap 'rm -rf "$unpacked"' EXIT
(cd "$unpacked" && jar xf "${war_files[0]}")

classpath="$unpacked/WEB-INF/classes"
for dependency in "$unpacked"/WEB-INF/lib/*.jar; do
  classpath="$classpath:$dependency"
done

java -cp "$classpath" att.server.SutMetricsCatalogMigration "$@"
