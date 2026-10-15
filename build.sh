#!/usr/bin/env sh
# Author: Jeffrey + ChatGPT
set -eu
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ "${1:-}" = "clean" ]; then
  rm -rf "$ROOT_DIR/target" "$ROOT_DIR/dist" "$ROOT_DIR/att-security/target" "$ROOT_DIR/att-engine/target" "$ROOT_DIR/att-cli/target" "$ROOT_DIR/att-worker/target" "$ROOT_DIR/att-web/target" "$ROOT_DIR/att-server/target" "$ROOT_DIR/att-dist/target"
  exit 0
fi

# Portable phase timings, including the phase that fails under set -e.
# Report to stderr so stdout remains only the release archive paths.
BUILD_STARTED_AT="$(date +%s)"
BUILD_PHASE_STARTED_AT="$BUILD_STARTED_AT"
BUILD_PHASE_NAME="Preflight"
build_timer_next() {
  now="$(date +%s)"
  printf '[ATT BUILD TIME] %s: %ss\n' "$BUILD_PHASE_NAME" "$((now - BUILD_PHASE_STARTED_AT))" >&2
  BUILD_PHASE_NAME="$1"
  BUILD_PHASE_STARTED_AT="$now"
}
build_timer_finish() {
  status=$?
  now="$(date +%s)"
  printf '[ATT BUILD TIME] %s: %ss%s\n' "$BUILD_PHASE_NAME" "$((now - BUILD_PHASE_STARTED_AT))" "$([ "$status" -eq 0 ] && printf '' || printf ' (FAILED)')" >&2
  printf '[ATT BUILD TIME] Total: %ss (exit %s)\n' "$((now - BUILD_STARTED_AT))" "$status" >&2
}
trap 'build_timer_finish' 0
for required in pom.xml att-security/pom.xml att-security/src/main/java att-engine/pom.xml att-engine/src/main/java att-cli/pom.xml att-cli/src/main/java att-worker/pom.xml att-worker/src/main/java att-web/pom.xml att-web/frontend/index.html att-server/pom.xml att-dist/pom.xml att-dist/src/assembly/local.xml att-dist/src/assembly/server.xml att-server/src/assembly/server-app.xml att-dist/src/release/local/RELEASE_MANIFEST.txt att-dist/src/release/server/RELEASE_MANIFEST.txt config templates tools testcase schemas att.sh att.bat README.md CHANGELOG.md docs/server-deployment.md docs/server-deployment.zh.md docs/att-server-web-ui.md docs/att-server-web-ui.zh.md; do
  if [ ! -e "$ROOT_DIR/$required" ]; then echo "Missing required package path: $ROOT_DIR/$required" >&2; exit 2; fi
done
VERSION="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$ROOT_DIR/pom.xml" | head -n 1)"
case "$VERSION" in ""|*-SNAPSHOT) echo "Release build requires a non-SNAPSHOT Maven project version" >&2; exit 2 ;; esac
LOCAL_PACKAGE_NAME="att-${VERSION}-local"
SERVER_PACKAGE_NAME="att-${VERSION}-server"
SOURCE_PACKAGE_NAME="att-${VERSION}-src"
DIST_DIR="$ROOT_DIR/dist/releases"
LOCAL_WORK="$ROOT_DIR/target/release/local"
SERVER_WORK="$ROOT_DIR/target/release/server"
LOCAL_ARCHIVE="$ROOT_DIR/att-dist/target/$LOCAL_PACKAGE_NAME.tar.gz"
SERVER_ARCHIVE="$ROOT_DIR/att-dist/target/$SERVER_PACKAGE_NAME.tar.gz"
SOURCE_ARCHIVE="$ROOT_DIR/att-dist/target/$SOURCE_PACKAGE_NAME.tar.gz"
MAX_BINARY_PACKAGE_BYTES=25000000
for command in mvn python3 tar; do
  if ! command -v "$command" >/dev/null 2>&1; then echo "$command is required to execute the ATT release gate" >&2; exit 2; fi
done
GIT_COMMIT="$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)"
build_timer_next "Reference manual"
(cd "$ROOT_DIR" && python3 tools/build_reference_manual.py)
set -- "-Datt.gitCommit=$GIT_COMMIT"
if [ "${ATT_CI_SKIP_COVERED_TESTS:-false}" = true ]; then
  set -- "$@" -Datt.skipJava8ModuleTests=true -Datt.skipFrontendUnitTests=true -Datt.skipServerUnitTests=true
fi
build_timer_next "Maven clean verify"
(cd "$ROOT_DIR" && mvn -B -ntp -T 1C "$@" clean verify)
build_timer_next "Source launcher checks"
(cd "$ROOT_DIR" && ./scripts/verify-source-launcher.sh)
build_timer_next "Source package validation"
(cd "$ROOT_DIR" && ATT_HOME="$ROOT_DIR" ATT_PACKAGE_ROOT="$ROOT_DIR" ORDERS_DB_USERNAME="${ORDERS_DB_USERNAME:-att-build-placeholder}" ORDERS_DB_PASSWORD="${ORDERS_DB_PASSWORD:-att-build-placeholder}" PAYMENT_MQ_USERNAME="${PAYMENT_MQ_USERNAME:-att-build-placeholder}" PAYMENT_MQ_PASSWORD="${PAYMENT_MQ_PASSWORD:-att-build-placeholder}" ./att.sh validate --package)
build_timer_next "Archive checks, repack and smoke tests"
for archive in "$LOCAL_ARCHIVE" "$SERVER_ARCHIVE" "$SOURCE_ARCHIVE"; do
  if [ ! -f "$archive" ]; then echo "Maven distribution assembly did not produce: $archive" >&2; exit 2; fi
done
for archive in "$LOCAL_ARCHIVE" "$SOURCE_ARCHIVE"; do
  if tar -tzf "$archive" | grep -E '(^|/)node_modules(/|$)' >/dev/null; then
    echo "Release archive must not include build-time Node.js dependencies: $archive" >&2; exit 2
  fi
  if tar -tzf "$archive" | grep -E '(^|/)ui-acceptance/artifacts(/|$)' >/dev/null; then
    echo "Release archive must not include generated UI acceptance captures: $archive" >&2; exit 2
  fi
done
if tar -tzf "$LOCAL_ARCHIVE" | grep -E '(^|/)ui-acceptance(/|$)' >/dev/null; then
  echo "Local binary archive must not include the development-only UI acceptance harness" >&2; exit 2
fi
rm -rf "$ROOT_DIR/target/release"; mkdir -p "$LOCAL_WORK" "$SERVER_WORK" "$DIST_DIR"
tar -xzf "$LOCAL_ARCHIVE" -C "$LOCAL_WORK"
LOCAL_PACKAGE_DIR="$LOCAL_WORK/$LOCAL_PACKAGE_NAME"
LOCAL_PACKAGE_ROOT="$LOCAL_PACKAGE_DIR/packages/default"
for required in att.sh att.bat lib config/att-server.example.yaml packages/default/config/config.yaml packages/default/config/tools packages/default/schemas packages/default/testcase packages/default/templates packages/default/tools; do
  if [ ! -e "$LOCAL_PACKAGE_DIR/$required" ]; then echo "Local archive is missing required layout path: $required" >&2; exit 2; fi
done
LOCAL_RUNTIME_JAR="$LOCAL_PACKAGE_DIR/lib/att-local-${VERSION}.jar"
if [ ! -f "$LOCAL_RUNTIME_JAR" ]; then
  echo "Local archive is missing its combined ATT runtime: lib/att-local-${VERSION}.jar" >&2; exit 2
fi
python3 - "$LOCAL_RUNTIME_JAR" <<'PYZIP'
import sys, zipfile
required = {"att-build.properties", "att/FrameworkRunner.class", "att/worker/WorkerMain.class"}
with zipfile.ZipFile(sys.argv[1]) as archive:
    missing = required.difference(archive.namelist())
if missing:
    raise SystemExit("Local runtime jar is incomplete: " + ", ".join(sorted(missing)))
PYZIP
if [ -e "$LOCAL_PACKAGE_DIR/config/att-server.yaml" ] || [ -e "$LOCAL_PACKAGE_ROOT/config/att-server.yaml" ] || [ -e "$LOCAL_PACKAGE_DIR/data" ]; then
  echo "Local archive must not contain an active Server config or Server data directory" >&2; exit 2
fi
mkdir -p "$LOCAL_PACKAGE_ROOT/output"
if tar -tzf "$LOCAL_ARCHIVE" | grep -F "server/att-server-${VERSION}.war" >/dev/null; then
  echo "Local package unexpectedly contains the Server WAR: $LOCAL_ARCHIVE" >&2; exit 2
fi

if [ -n "${IBM_MQ_JAR:-}" ]; then
  if [ ! -f "$IBM_MQ_JAR" ]; then echo "IBM_MQ_JAR does not point to a file: $IBM_MQ_JAR" >&2; exit 2; fi
  cp "$IBM_MQ_JAR" "$LOCAL_PACKAGE_DIR/lib/"
fi

# Repack the exact local tree delivered to users and smoke-test that archive.
rm -f "$LOCAL_ARCHIVE"
(cd "$LOCAL_WORK" && COPYFILE_DISABLE=1 tar --no-xattrs -czf "$LOCAL_ARCHIVE" "$LOCAL_PACKAGE_NAME")
rm -rf "$LOCAL_PACKAGE_DIR"
tar -xzf "$LOCAL_ARCHIVE" -C "$LOCAL_WORK"
LOCAL_PACKAGE_DIR="$LOCAL_WORK/$LOCAL_PACKAGE_NAME"

(
  cd "$LOCAL_PACKAGE_DIR"
  ORDERS_DB_USERNAME="${ORDERS_DB_USERNAME:-att-build-placeholder}"
  ORDERS_DB_PASSWORD="${ORDERS_DB_PASSWORD:-att-build-placeholder}"
  PAYMENT_MQ_USERNAME="${PAYMENT_MQ_USERNAME:-att-build-placeholder}"
  PAYMENT_MQ_PASSWORD="${PAYMENT_MQ_PASSWORD:-att-build-placeholder}"
  export ORDERS_DB_USERNAME ORDERS_DB_PASSWORD PAYMENT_MQ_USERNAME PAYMENT_MQ_PASSWORD
  ./att.sh version | grep -Fx "ATT V$VERSION" >/dev/null
  ./att.sh help >/dev/null
  ./att.sh remote help >/dev/null
  ./att.sh debug >/dev/null
  ./att.sh load >/dev/null
  ./att.sh validate --package
)

tar -xzf "$SERVER_ARCHIVE" -C "$SERVER_WORK"
SERVER_PACKAGE_DIR="$SERVER_WORK/$SERVER_PACKAGE_NAME"
if [ ! -f "$SERVER_PACKAGE_DIR/server/att-server-${VERSION}.war" ]; then
  echo "Server package is missing its WAR: $SERVER_ARCHIVE" >&2; exit 2
fi

# Repack and re-extract the exact server tree before checking its final size.
rm -f "$SERVER_ARCHIVE"
(cd "$SERVER_WORK" && COPYFILE_DISABLE=1 tar --no-xattrs -czf "$SERVER_ARCHIVE" "$SERVER_PACKAGE_NAME")
rm -rf "$SERVER_PACKAGE_DIR"
tar -xzf "$SERVER_ARCHIVE" -C "$SERVER_WORK"
SERVER_PACKAGE_DIR="$SERVER_WORK/$SERVER_PACKAGE_NAME"
if [ ! -f "$SERVER_PACKAGE_DIR/server/att-server-${VERSION}.war" ]; then
  echo "Final Server package is missing its WAR: $SERVER_ARCHIVE" >&2; exit 2
fi

for archive in "$LOCAL_ARCHIVE" "$SERVER_ARCHIVE"; do
  archive_size=$(wc -c < "$archive" | tr -d '[:space:]')
  if [ "$archive_size" -ge "$MAX_BINARY_PACKAGE_BYTES" ]; then
    echo "Binary package must be smaller than 25 MB ($MAX_BINARY_PACKAGE_BYTES bytes): $archive ($archive_size bytes)" >&2; exit 2
  fi
  echo "Verified package size: $archive ($archive_size bytes)"
done

build_timer_next "Release publication"
cp "$LOCAL_ARCHIVE" "$DIST_DIR/$LOCAL_PACKAGE_NAME.tar.gz"
cp "$SERVER_ARCHIVE" "$DIST_DIR/$SERVER_PACKAGE_NAME.tar.gz"
cp "$SOURCE_ARCHIVE" "$DIST_DIR/$SOURCE_PACKAGE_NAME.tar.gz"
echo "$DIST_DIR/$LOCAL_PACKAGE_NAME.tar.gz"
echo "$DIST_DIR/$SERVER_PACKAGE_NAME.tar.gz"
echo "$DIST_DIR/$SOURCE_PACKAGE_NAME.tar.gz"
