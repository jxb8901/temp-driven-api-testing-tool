#!/usr/bin/env sh
# Author: Jeffrey + ChatGPT
set -eu
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ "${1:-}" = "clean" ]; then
  rm -rf "$ROOT_DIR/target" "$ROOT_DIR/dist" "$ROOT_DIR/att-engine/target" "$ROOT_DIR/att-cli/target" "$ROOT_DIR/att-worker/target" "$ROOT_DIR/att-web/target" "$ROOT_DIR/att-server/target" "$ROOT_DIR/att-dist/target"
  exit 0
fi
for required in pom.xml att-engine/pom.xml att-engine/src/main/java att-cli/pom.xml att-cli/src/main/java att-worker/pom.xml att-worker/src/main/java att-web/pom.xml att-web/src/main/resources/META-INF/resources/ui/index.html att-server/pom.xml att-dist/pom.xml att-dist/src/assembly/local.xml att-dist/src/assembly/server.xml att-dist/src/release/local/RELEASE_MANIFEST.txt att-dist/src/release/server/RELEASE_MANIFEST.txt config templates tools testcase schemas att.sh att.bat README.md CHANGELOG.md docs/server-deployment.md docs/server-deployment.zh.md docs/att-server-web-ui.md docs/att-server-web-ui.zh.md; do
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
for command in mvn python3 pandoc tar; do
  if ! command -v "$command" >/dev/null 2>&1; then echo "$command is required to execute the ATT release gate" >&2; exit 2; fi
done
GIT_COMMIT="$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)"
(cd "$ROOT_DIR" && python3 tools/build_reference_manual.py)
(cd "$ROOT_DIR" && mvn -B -ntp -Datt.gitCommit="$GIT_COMMIT" clean verify)
(cd "$ROOT_DIR" && ./scripts/verify-source-launcher.sh)
(cd "$ROOT_DIR" && ORDERS_DB_USERNAME="${ORDERS_DB_USERNAME:-att-build-placeholder}" ORDERS_DB_PASSWORD="${ORDERS_DB_PASSWORD:-att-build-placeholder}" PAYMENT_MQ_USERNAME="${PAYMENT_MQ_USERNAME:-att-build-placeholder}" PAYMENT_MQ_PASSWORD="${PAYMENT_MQ_PASSWORD:-att-build-placeholder}" ./att.sh validate --package)
for archive in "$LOCAL_ARCHIVE" "$SERVER_ARCHIVE" "$SOURCE_ARCHIVE"; do
  if [ ! -f "$archive" ]; then echo "Maven distribution assembly did not produce: $archive" >&2; exit 2; fi
done
rm -rf "$ROOT_DIR/target/release"; mkdir -p "$LOCAL_WORK" "$SERVER_WORK" "$DIST_DIR"
tar -xzf "$LOCAL_ARCHIVE" -C "$LOCAL_WORK"
LOCAL_PACKAGE_DIR="$LOCAL_WORK/$LOCAL_PACKAGE_NAME"
mkdir -p "$LOCAL_PACKAGE_DIR/output"
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

cp "$LOCAL_ARCHIVE" "$DIST_DIR/$LOCAL_PACKAGE_NAME.tar.gz"
cp "$SERVER_ARCHIVE" "$DIST_DIR/$SERVER_PACKAGE_NAME.tar.gz"
cp "$SOURCE_ARCHIVE" "$DIST_DIR/$SOURCE_PACKAGE_NAME.tar.gz"
echo "$DIST_DIR/$LOCAL_PACKAGE_NAME.tar.gz"
echo "$DIST_DIR/$SERVER_PACKAGE_NAME.tar.gz"
echo "$DIST_DIR/$SOURCE_PACKAGE_NAME.tar.gz"
