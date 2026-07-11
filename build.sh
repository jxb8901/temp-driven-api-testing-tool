#!/usr/bin/env sh
# Author: Jeffrey + ChatGPT
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ "${1:-}" = "clean" ]; then
  rm -rf "$ROOT_DIR/target" "$ROOT_DIR/dist"
  exit 0
fi
for required in pom.xml src/main/java config templates tools testcase schemas att.sh README.md CHANGELOG.md; do
  if [ ! -e "$ROOT_DIR/$required" ]; then
    echo "Missing required package path: $ROOT_DIR/$required" >&2
    exit 2
  fi
done
VERSION="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$ROOT_DIR/pom.xml" | head -n 1)"
case "$VERSION" in
  ""|*-SNAPSHOT) echo "Release build requires a non-SNAPSHOT Maven project version" >&2; exit 2 ;;
esac
PACKAGE_NAME="att-${VERSION}"
SOURCE_PACKAGE_NAME="att-${VERSION}-src"
BUILD_DIR="$ROOT_DIR/target/release"
PACKAGE_DIR="$BUILD_DIR/$PACKAGE_NAME"
SOURCE_PACKAGE_DIR="$BUILD_DIR/$SOURCE_PACKAGE_NAME"
DIST_DIR="$ROOT_DIR/dist/releases"
M2_REPO="${M2_REPO:-$HOME/.m2/repository}"
CLASS_DIR="$BUILD_DIR/classes"
APP_JAR="$PACKAGE_DIR/lib/att-${VERSION}.jar"

DEPS="
com/fasterxml/jackson/core/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar
com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar
com/fasterxml/jackson/core/jackson-databind/2.17.2/jackson-databind-2.17.2.jar
commons-io/commons-io/2.16.1/commons-io-2.16.1.jar
org/apache/commons/commons-compress/1.25.0/commons-compress-1.25.0.jar
org/apache/commons/commons-collections4/4.4/commons-collections4-4.4.jar
org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar
org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar
org/apache/xmlbeans/xmlbeans/5.2.0/xmlbeans-5.2.0.jar
org/apache/poi/poi-ooxml/5.2.5/poi-ooxml-5.2.5.jar
org/apache/poi/poi/5.2.5/poi-5.2.5.jar
org/apache/poi/poi-ooxml-lite/5.2.5/poi-ooxml-lite-5.2.5.jar
com/github/virtuald/curvesapi/1.08/curvesapi-1.08.jar
org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar
org/apache/logging/log4j/log4j-api/2.21.1/log4j-api-2.21.1.jar
"

if ! command -v javac >/dev/null 2>&1; then
  echo "javac is required. Please install JDK 8 or JDK 11." >&2
  exit 2
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "mvn is required to execute the V2.1 release gate" >&2
  exit 2
fi

(cd "$ROOT_DIR" && mvn test)
(cd "$ROOT_DIR" && ./att.sh validate --package)

rm -rf "$PACKAGE_DIR"
rm -rf "$SOURCE_PACKAGE_DIR"
rm -rf "$CLASS_DIR"
rm -f "$DIST_DIR/$PACKAGE_NAME.tar.gz" "$DIST_DIR/$SOURCE_PACKAGE_NAME.tar.gz"
mkdir -p "$PACKAGE_DIR/lib" "$DIST_DIR"
mkdir -p "$CLASS_DIR"

BUILD_TIME="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
GIT_COMMIT="$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)"

CP=""
for dep in $DEPS; do
  jar="$M2_REPO/$dep"
  if [ ! -f "$jar" ]; then
    echo "Missing dependency jar: $jar" >&2
    echo "Run Maven once on a development machine, then rerun this script." >&2
    exit 2
  fi
  cp "$jar" "$PACKAGE_DIR/lib/"
  CP="${CP:+$CP:}$jar"
done

find "$ROOT_DIR/src/main/java" -name '*.java' | sort > "$BUILD_DIR/sources.txt"
if javac --help 2>&1 | grep -q -- '--release'; then
  javac --release 8 -cp "$CP" -d "$CLASS_DIR" @"$BUILD_DIR/sources.txt"
else
  javac -source 1.8 -target 1.8 -cp "$CP" -d "$CLASS_DIR" @"$BUILD_DIR/sources.txt"
fi
if [ -d "$ROOT_DIR/src/main/resources" ]; then
  cp -R "$ROOT_DIR/src/main/resources/." "$CLASS_DIR/"
fi

{
  echo "att.version=$VERSION"
  echo "att.buildTime=$BUILD_TIME"
  echo "att.gitCommit=$GIT_COMMIT"
} > "$CLASS_DIR/att-build.properties"

if command -v jar >/dev/null 2>&1; then
  jar cf "$APP_JAR" -C "$CLASS_DIR" .
else
  echo "jar is required to create the ATT application artifact." >&2
  exit 2
fi

cp "$ROOT_DIR/att.sh" "$PACKAGE_DIR/att.sh"
cp "$ROOT_DIR/README.md" "$PACKAGE_DIR/README.md"
cp "$ROOT_DIR/CHANGELOG.md" "$PACKAGE_DIR/CHANGELOG.md"
cp -R "$ROOT_DIR/config" "$PACKAGE_DIR/config"
cp -R "$ROOT_DIR/templates" "$PACKAGE_DIR/templates"
cp -R "$ROOT_DIR/tools" "$PACKAGE_DIR/tools"
cp -R "$ROOT_DIR/testcase" "$PACKAGE_DIR/testcase"
[ ! -d "$ROOT_DIR/docs" ] || cp -R "$ROOT_DIR/docs" "$PACKAGE_DIR/docs"
[ ! -d "$ROOT_DIR/schemas" ] || cp -R "$ROOT_DIR/schemas" "$PACKAGE_DIR/schemas"
mkdir -p "$PACKAGE_DIR/output"
find "$PACKAGE_DIR" -name '.DS_Store' -delete

chmod +x "$PACKAGE_DIR/att.sh"
find "$PACKAGE_DIR/tools" -type f -name '*.sh' -exec chmod +x {} \;

{
  echo "name: $PACKAGE_NAME"
  echo "main: att.sh"
  echo "java: JDK 8+ runtime"
  echo "contents:"
  echo "  - lib/att-${VERSION}.jar"
  echo "  - lib/"
  echo "  - config/"
  echo "  - templates/"
  echo "  - tools/"
  echo "  - testcase/"
  echo "  - docs/"
  echo "  - schemas/"
} > "$PACKAGE_DIR/RELEASE_MANIFEST.txt"

(cd "$PACKAGE_DIR" && ./att.sh version | grep -Fx "ATT V$VERSION" >/dev/null)
(cd "$PACKAGE_DIR" && ./att.sh validate --package)

(cd "$BUILD_DIR" && tar -czf "$DIST_DIR/$PACKAGE_NAME.tar.gz" "$PACKAGE_NAME")

mkdir -p "$SOURCE_PACKAGE_DIR"
cp "$ROOT_DIR/att.sh" "$SOURCE_PACKAGE_DIR/att.sh"
cp "$ROOT_DIR/build.sh" "$SOURCE_PACKAGE_DIR/build.sh"
cp "$ROOT_DIR/README.md" "$SOURCE_PACKAGE_DIR/README.md"
cp "$ROOT_DIR/CHANGELOG.md" "$SOURCE_PACKAGE_DIR/CHANGELOG.md"
cp "$ROOT_DIR/pom.xml" "$SOURCE_PACKAGE_DIR/pom.xml"
cp -R "$ROOT_DIR/config" "$SOURCE_PACKAGE_DIR/config"
cp -R "$ROOT_DIR/templates" "$SOURCE_PACKAGE_DIR/templates"
cp -R "$ROOT_DIR/tools" "$SOURCE_PACKAGE_DIR/tools"
cp -R "$ROOT_DIR/testcase" "$SOURCE_PACKAGE_DIR/testcase"
[ ! -d "$ROOT_DIR/docs" ] || cp -R "$ROOT_DIR/docs" "$SOURCE_PACKAGE_DIR/docs"
[ ! -d "$ROOT_DIR/schemas" ] || cp -R "$ROOT_DIR/schemas" "$SOURCE_PACKAGE_DIR/schemas"
cp -R "$ROOT_DIR/src" "$SOURCE_PACKAGE_DIR/src"
cp "$ROOT_DIR/.gitignore" "$SOURCE_PACKAGE_DIR/.gitignore"
mkdir -p "$SOURCE_PACKAGE_DIR/output" "$SOURCE_PACKAGE_DIR/report" "$SOURCE_PACKAGE_DIR/logs"
find "$SOURCE_PACKAGE_DIR" -name '.DS_Store' -delete

chmod +x "$SOURCE_PACKAGE_DIR/att.sh"
chmod +x "$SOURCE_PACKAGE_DIR/build.sh"
find "$SOURCE_PACKAGE_DIR/tools" -type f -name '*.sh' -exec chmod +x {} \;

{
  echo "name: $SOURCE_PACKAGE_NAME"
  echo "main: att.sh"
  echo "contents:"
  echo "  - src/"
  echo "  - templates/"
  echo "  - tools/"
  echo "  - testcase/"
  echo "  - config/"
  echo "  - docs/"
  echo "  - schemas/"
  echo "  - README.md"
  echo "  - CHANGELOG.md"
  echo "  - pom.xml"
  echo "  - att.sh"
  echo "  - build.sh"
} > "$SOURCE_PACKAGE_DIR/RELEASE_MANIFEST.txt"

(cd "$BUILD_DIR" && tar -czf "$DIST_DIR/$SOURCE_PACKAGE_NAME.tar.gz" "$SOURCE_PACKAGE_NAME")
echo "$DIST_DIR/$PACKAGE_NAME.tar.gz"
echo "$DIST_DIR/$SOURCE_PACKAGE_NAME.tar.gz"
