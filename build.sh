#!/usr/bin/env sh
# Author: Jeffrey + ChatGPT
set -eu
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ "${1:-}" = "clean" ]; then
  rm -rf "$ROOT_DIR/target" "$ROOT_DIR/dist" "$ROOT_DIR/att-cli/target" "$ROOT_DIR/att-server/target" "$ROOT_DIR/att-dist/target"
  exit 0
fi
for required in pom.xml att-cli/pom.xml att-cli/src/main/java att-server/pom.xml att-dist/pom.xml config templates tools testcase schemas att.sh att.bat README.md CHANGELOG.md; do
  if [ ! -e "$ROOT_DIR/$required" ]; then echo "Missing required package path: $ROOT_DIR/$required" >&2; exit 2; fi
done
VERSION="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$ROOT_DIR/pom.xml" | head -n 1)"
case "$VERSION" in ""|*-SNAPSHOT) echo "Release build requires a non-SNAPSHOT Maven project version" >&2; exit 2 ;; esac
PACKAGE_NAME="att-${VERSION}"
SOURCE_PACKAGE_NAME="att-${VERSION}-src"
DIST_DIR="$ROOT_DIR/dist/releases"
RELEASE_WORK="$ROOT_DIR/target/release"
BINARY_ARCHIVE="$ROOT_DIR/att-dist/target/$PACKAGE_NAME.tar.gz"
SOURCE_ARCHIVE="$ROOT_DIR/att-dist/target/$SOURCE_PACKAGE_NAME.tar.gz"
for command in mvn python3 pandoc tar; do
  if ! command -v "$command" >/dev/null 2>&1; then echo "$command is required to execute the ATT release gate" >&2; exit 2; fi
done
GIT_COMMIT="$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)"
(cd "$ROOT_DIR" && python3 tools/build_reference_manual.py)
(cd "$ROOT_DIR" && mvn -B -ntp -Datt.gitCommit="$GIT_COMMIT" clean verify)
(cd "$ROOT_DIR" && ORDERS_DB_USERNAME="${ORDERS_DB_USERNAME:-att-build-placeholder}" ORDERS_DB_PASSWORD="${ORDERS_DB_PASSWORD:-att-build-placeholder}" PAYMENT_MQ_USERNAME="${PAYMENT_MQ_USERNAME:-att-build-placeholder}" PAYMENT_MQ_PASSWORD="${PAYMENT_MQ_PASSWORD:-att-build-placeholder}" ./att.sh validate --package)
for archive in "$BINARY_ARCHIVE" "$SOURCE_ARCHIVE"; do
  if [ ! -f "$archive" ]; then echo "Maven distribution assembly did not produce: $archive" >&2; exit 2; fi
done
rm -rf "$RELEASE_WORK"; mkdir -p "$RELEASE_WORK" "$DIST_DIR"
tar -xzf "$BINARY_ARCHIVE" -C "$RELEASE_WORK"
PACKAGE_DIR="$RELEASE_WORK/$PACKAGE_NAME"
mkdir -p "$PACKAGE_DIR/output"
if [ -n "${IBM_MQ_JAR:-}" ]; then
  if [ ! -f "$IBM_MQ_JAR" ]; then echo "IBM_MQ_JAR does not point to a file: $IBM_MQ_JAR" >&2; exit 2; fi
  cp "$IBM_MQ_JAR" "$PACKAGE_DIR/lib/"
  rm -f "$BINARY_ARCHIVE"
  (cd "$RELEASE_WORK" && COPYFILE_DISABLE=1 tar --no-xattrs -czf "$BINARY_ARCHIVE" "$PACKAGE_NAME")
fi
(
  cd "$PACKAGE_DIR"
  ORDERS_DB_USERNAME="${ORDERS_DB_USERNAME:-att-build-placeholder}"
  ORDERS_DB_PASSWORD="${ORDERS_DB_PASSWORD:-att-build-placeholder}"
  PAYMENT_MQ_USERNAME="${PAYMENT_MQ_USERNAME:-att-build-placeholder}"
  PAYMENT_MQ_PASSWORD="${PAYMENT_MQ_PASSWORD:-att-build-placeholder}"
  export ORDERS_DB_USERNAME ORDERS_DB_PASSWORD PAYMENT_MQ_USERNAME PAYMENT_MQ_PASSWORD
  ./att.sh version | grep -Fx "ATT V$VERSION" >/dev/null
  ./att.sh help >/dev/null
  ./att.sh debug >/dev/null
  ./att.sh load >/dev/null
  ./att.sh validate --package
)
cp "$BINARY_ARCHIVE" "$DIST_DIR/$PACKAGE_NAME.tar.gz"
cp "$SOURCE_ARCHIVE" "$DIST_DIR/$SOURCE_PACKAGE_NAME.tar.gz"
echo "$DIST_DIR/$PACKAGE_NAME.tar.gz"
echo "$DIST_DIR/$SOURCE_PACKAGE_NAME.tar.gz"
