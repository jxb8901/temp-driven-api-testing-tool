#!/usr/bin/env sh
# Verify source and packaged launchers run prebuilt classes without compiling.
set -eu
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/att-launcher.XXXXXX")"
SOURCE_DEBUG_FIXTURE=""
trap 'rm -rf "$TEMP_DIR"; if [ -n "$SOURCE_DEBUG_FIXTURE" ]; then rm -rf "$ROOT_DIR/$SOURCE_DEBUG_FIXTURE"; fi' EXIT HUP INT TERM

JAVA_BIN="$(command -v java)"
DIRNAME_BIN="$(command -v dirname)"
VERSION="$(awk -F'[<>]' '
  /<artifactId>template-driven-api-testing-tool<\/artifactId>/ { parent=1; next }
  parent && /<version>/ { print $3; exit }
' "$ROOT_DIR/pom.xml")"
PACKAGE_ARCHIVE="$ROOT_DIR/att-dist/target/att-$VERSION-local.tar.gz"
PACKAGE_PARENT="$TEMP_DIR/package"

make_path() {
  path_dir="$1"
  include_maven="$2"
  mkdir -p "$path_dir"
  ln -s "$JAVA_BIN" "$path_dir/java"
  ln -s "$DIRNAME_BIN" "$path_dir/dirname"
  cat > "$path_dir/javac" <<'SH'
#!/bin/sh
printf 'javac\n' >> "$ATT_BUILD_TOOL_LOG"
exit 97
SH
  chmod +x "$path_dir/javac"
  if [ "$include_maven" = true ]; then
    cat > "$path_dir/mvn" <<'SH'
#!/bin/sh
printf 'mvn\n' >> "$ATT_BUILD_TOOL_LOG"
exit 97
SH
    chmod +x "$path_dir/mvn"
  fi
}

run_and_check() {
  path_dir="$1"
  log_file="$2"
  launcher="$3"
  shift 3
  output="$(PATH="$path_dir" ATT_BUILD_TOOL_LOG="$log_file" \
    ORDERS_DB_USERNAME=att-validation-placeholder ORDERS_DB_PASSWORD=att-validation-placeholder \
    PAYMENT_MQ_USERNAME=att-validation-placeholder PAYMENT_MQ_PASSWORD=att-validation-placeholder \
    /bin/sh "$launcher" "$@")" || {
    status=$?
    echo "Launcher failed (exit $status): $launcher $*" >&2
    echo "$output" >&2
    exit 1
  }
  if [ -n "$log_file" ] && [ -s "$log_file" ]; then
    echo "Launcher invoked a build tool: $launcher $*" >&2
    cat "$log_file" >&2
    exit 1
  fi
}

run_commands() {
  root="$1"
  path_dir="$2"
  log_file="$3"
  for command in version help debug; do
    run_and_check "$path_dir" "$log_file" "$root/att.sh" "$command"
  done
  run_and_check "$path_dir" "$log_file" "$root/att.sh" remote help
}

write_debug_fixture() {
  root="$1"
  fixture_relative="target/launcher-debug-smoke-$$"
  fixture="$root/$fixture_relative"
  mkdir -p "$fixture/templates/SIMPLE"
  cat > "$fixture/config.yaml" <<EOF
schemaVersion: att-config/v2.11
environment: SIT
outputDirectory: $fixture_relative/output
templates: {root: $fixture_relative/templates}
testcase: {root: testcase}
EOF
  cat > "$fixture/templates/SIMPLE/template.yaml" <<'EOF'
schemaVersion: att-template/v3.4
name: SIMPLE
description: Source launcher Debug execution smoke.
actions:
  record:
    type: log
    message: launcher-debug-smoke
EOF
  cat > "$fixture/templates/SIMPLE/debug.yaml" <<'EOF'
schemaVersion: att-debug/v1.2
inputs: {}
EOF
  printf '%s\n' "$fixture_relative"
}

run_debug() {
  root="$1"
  path_dir="$2"
  log_file="$3"
  fixture_relative="$4"
  output="$(PATH="$path_dir" ATT_BUILD_TOOL_LOG="$log_file" \
    /bin/sh "$root/att.sh" debug template SIMPLE \
    --config "$fixture_relative/config.yaml" \
    --input "$fixture_relative/templates/SIMPLE/debug.yaml" --format json --quiet)" || {
    status=$?
    echo "Debug launcher failed (exit $status): $root/att.sh debug template SIMPLE" >&2
    echo "$output" >&2
    exit 1
  }
  if ! printf '%s' "$output" | grep -F 'PASS' >/dev/null; then
    echo "Debug launcher did not execute the fixture successfully:" >&2
    echo "$output" >&2
    exit 1
  fi
  if [ -n "$log_file" ] && [ -s "$log_file" ]; then
    echo "Debug launcher invoked a build tool:" >&2
    cat "$log_file" >&2
    exit 1
  fi
}

MAVEN_PATH="$TEMP_DIR/path-with-maven"
NO_MAVEN_PATH="$TEMP_DIR/path-without-maven"
MAVEN_LOG="$TEMP_DIR/build-tools-with-maven.log"
NO_MAVEN_LOG="$TEMP_DIR/build-tools-without-maven.log"
make_path "$MAVEN_PATH" true
make_path "$NO_MAVEN_PATH" false

for required in \
  "$ROOT_DIR/att-cli/target/classes/att/FrameworkRunner.class" \
  "$ROOT_DIR/att-engine/target/classes/att/Version.class" \
  "$ROOT_DIR/att-remote/target/classes/att/remote/RemoteCommand.class" \
  "$ROOT_DIR/att-server-api/target/classes/att/server/api/ServerApi.class"; do
  if [ ! -f "$required" ]; then
    echo "Source launcher check needs Maven-built classes; missing: $required" >&2
    echo "Run mvn -DskipTests -pl att-cli -am compile before this check." >&2
    exit 2
  fi
done

run_commands "$ROOT_DIR" "$MAVEN_PATH" "$MAVEN_LOG"
run_commands "$ROOT_DIR" "$NO_MAVEN_PATH" "$NO_MAVEN_LOG"
SOURCE_DEBUG_FIXTURE="$(write_debug_fixture "$ROOT_DIR")"
run_debug "$ROOT_DIR" "$MAVEN_PATH" "$MAVEN_LOG" "$SOURCE_DEBUG_FIXTURE"
run_debug "$ROOT_DIR" "$NO_MAVEN_PATH" "$NO_MAVEN_LOG" "$SOURCE_DEBUG_FIXTURE"

MISSING_ROOT="$TEMP_DIR/missing-source"
mkdir -p "$MISSING_ROOT/att-cli/target/classes"
cp "$ROOT_DIR/att.sh" "$MISSING_ROOT/att.sh"
for path_dir in "$MAVEN_PATH" "$NO_MAVEN_PATH"; do
  log_file="$TEMP_DIR/missing-$([ "$path_dir" = "$MAVEN_PATH" ] && echo maven || echo no-maven).log"
  set +e
  output="$(PATH="$path_dir" ATT_BUILD_TOOL_LOG="$log_file" /bin/sh "$MISSING_ROOT/att.sh" version 2>&1)"
  status=$?
  set -e
  if [ "$status" -ne 2 ] ||
      ! printf '%s' "$output" | grep -F 'missing required prebuilt classes' >/dev/null ||
      ! printf '%s' "$output" | grep -F 'mvn -DskipTests -pl att-cli -am compile' >/dev/null; then
    echo "Missing-class launcher error was not actionable (exit $status):" >&2
    echo "$output" >&2
    exit 1
  fi
  if [ -s "$log_file" ]; then
    echo "Missing-class launcher attempted a build:" >&2
    cat "$log_file" >&2
    exit 1
  fi
done

if [ ! -f "$PACKAGE_ARCHIVE" ]; then
  echo "Packaged launcher check needs the local release archive: $PACKAGE_ARCHIVE" >&2
  exit 2
fi
mkdir -p "$PACKAGE_PARENT"
tar -xzf "$PACKAGE_ARCHIVE" -C "$PACKAGE_PARENT"
PACKAGE_ROOT="$PACKAGE_PARENT/att-$VERSION-local"
run_commands "$PACKAGE_ROOT" "$MAVEN_PATH" "$MAVEN_LOG"
run_commands "$PACKAGE_ROOT" "$NO_MAVEN_PATH" "$NO_MAVEN_LOG"
PACKAGE_DEBUG_FIXTURE="$(write_debug_fixture "$PACKAGE_ROOT")"
run_debug "$PACKAGE_ROOT" "$MAVEN_PATH" "$MAVEN_LOG" "$PACKAGE_DEBUG_FIXTURE"
run_debug "$PACKAGE_ROOT" "$NO_MAVEN_PATH" "$MAVEN_LOG" "$PACKAGE_DEBUG_FIXTURE"
rm -rf "$ROOT_DIR/$SOURCE_DEBUG_FIXTURE"

echo "Source and packaged launchers passed run-only and Debug execution checks with Maven present and absent."
