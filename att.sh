#!/usr/bin/env sh
# Author: Jeffrey + ChatGPT
set -eu
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$ROOT_DIR"
if [ "${1:-}" = "validate" ]; then
  ORDERS_DB_USERNAME="${ORDERS_DB_USERNAME:-att-validation-placeholder}"; ORDERS_DB_PASSWORD="${ORDERS_DB_PASSWORD:-att-validation-placeholder}"
  PAYMENT_MQ_USERNAME="${PAYMENT_MQ_USERNAME:-att-validation-placeholder}"; PAYMENT_MQ_PASSWORD="${PAYMENT_MQ_PASSWORD:-att-validation-placeholder}"
  export ORDERS_DB_USERNAME ORDERS_DB_PASSWORD PAYMENT_MQ_USERNAME PAYMENT_MQ_PASSWORD
fi
APP_JAR=""
for candidate in "$ROOT_DIR"/lib/att-*.jar; do if [ -f "$candidate" ]; then APP_JAR="$candidate"; break; fi; done
if [ -n "$APP_JAR" ]; then
  CP="$APP_JAR"
  for jar in "$ROOT_DIR"/lib/*.jar; do [ -f "$jar" ] || continue; [ "$jar" = "$APP_JAR" ] && continue; CP="$CP:$jar"; done
  exec java -cp "$CP" att.FrameworkRunner "$@"
fi
CLI_DIR="$ROOT_DIR/att-cli"
ENGINE_DIR="$ROOT_DIR/att-engine"
CP="$CLI_DIR/target/classes:$CLI_DIR/target/test-classes:$ENGINE_DIR/target/classes:$ROOT_DIR/att-remote/target/classes:$ROOT_DIR/att-server-api/target/classes"
for jar in \
  "$HOME"/.m2/repository/commons-io/commons-io/2.16.1/commons-io-2.16.1.jar \
  "$HOME"/.m2/repository/org/apache/httpcomponents/httpclient/4.5.13/httpclient-4.5.13.jar \
  "$HOME"/.m2/repository/org/apache/httpcomponents/httpcore/4.4.13/httpcore-4.4.13.jar \
  "$HOME"/.m2/repository/commons-codec/commons-codec/1.11/commons-codec-1.11.jar \
  "$HOME"/.m2/repository/commons-logging/commons-logging/1.2/commons-logging-1.2.jar \
  "$HOME"/.m2/repository/com/fasterxml/jackson/core/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar \
  "$HOME"/.m2/repository/com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar \
  "$HOME"/.m2/repository/com/fasterxml/jackson/core/jackson-databind/2.17.2/jackson-databind-2.17.2.jar \
  "$HOME"/.m2/repository/com/networknt/json-schema-validator/1.4.0/json-schema-validator-1.4.0.jar \
  "$HOME"/.m2/repository/com/zaxxer/HikariCP/4.0.3/HikariCP-4.0.3.jar \
  "$HOME"/.m2/repository/com/github/mwiede/jsch/2.28.2/jsch-2.28.2.jar \
  "$HOME"/.m2/repository/com/ethlo/time/itu/1.8.0/itu-1.8.0.jar \
  "$HOME"/.m2/repository/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar \
  "$HOME"/.m2/repository/org/slf4j/slf4j-nop/2.0.9/slf4j-nop-2.0.9.jar \
  "$HOME"/.m2/repository/org/apache/commons/commons-compress/1.25.0/commons-compress-1.25.0.jar \
  "$HOME"/.m2/repository/org/apache/commons/commons-collections4/4.4/commons-collections4-4.4.jar \
  "$HOME"/.m2/repository/org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar \
  "$HOME"/.m2/repository/org/apache/commons/commons-lang3/3.12.0/commons-lang3-3.12.0.jar \
  "$HOME"/.m2/repository/org/apache/xmlbeans/xmlbeans/5.2.0/xmlbeans-5.2.0.jar \
  "$HOME"/.m2/repository/org/apache/poi/poi-ooxml/5.2.5/poi-ooxml-5.2.5.jar \
  "$HOME"/.m2/repository/org/apache/poi/poi/5.2.5/poi-5.2.5.jar \
  "$HOME"/.m2/repository/org/apache/poi/poi-ooxml-lite/5.2.5/poi-ooxml-lite-5.2.5.jar \
  "$HOME"/.m2/repository/com/github/virtuald/curvesapi/1.08/curvesapi-1.08.jar \
  "$HOME"/.m2/repository/org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar \
  "$HOME"/.m2/repository/org/apache/logging/log4j/log4j-api/2.21.1/log4j-api-2.21.1.jar; do CP="$CP:$jar"; done
for jar in "$ROOT_DIR"/lib/*.jar; do [ -f "$jar" ] || continue; CP="$CP:$jar"; done
NEEDS_BUILD=false
BUILD_MARKER="$CLI_DIR/target/classes/att-build.properties"
ENGINE_BUILD_MARKER="$ENGINE_DIR/target/classes/att-build.properties"
REMOTE_BUILD_MARKER="$ROOT_DIR/att-remote/target/classes/att/remote/RemoteCommand.class"
API_BUILD_MARKER="$ROOT_DIR/att-server-api/target/classes/att/server/api/ServerApi.class"
if [ "${ATT_FORCE_JAVAC:-false}" = true ] || [ ! -f "$BUILD_MARKER" ] || [ ! -f "$ENGINE_BUILD_MARKER" ] || [ ! -f "$REMOTE_BUILD_MARKER" ] || [ ! -f "$API_BUILD_MARKER" ]; then NEEDS_BUILD=true
elif find "$CLI_DIR/src/main/java" "$ENGINE_DIR/src/main/java" -name '*.java' -newer "$BUILD_MARKER" -print -quit | grep -q .; then NEEDS_BUILD=true
elif find "$ENGINE_DIR/src/main/java" -name '*.java' -newer "$ENGINE_BUILD_MARKER" -print -quit | grep -q .; then NEEDS_BUILD=true
elif find "$ROOT_DIR/att-remote/src/main/java" "$ROOT_DIR/att-server-api/src/main/java" -name '*.java' -newer "$REMOTE_BUILD_MARKER" -print -quit | grep -q .; then NEEDS_BUILD=true; fi
if [ "$NEEDS_BUILD" = true ]; then
  echo "Compiling ATT sources..."
  if [ "${ATT_FORCE_JAVAC:-false}" != true ] && command -v mvn >/dev/null 2>&1; then
    (cd "$ROOT_DIR" && mvn -q -DskipTests -T 1C -pl att-cli -am compile)
  elif command -v javac >/dev/null 2>&1; then
    ENGINE_CLASSES="$ENGINE_DIR/target/classes"; CLI_CLASSES="$CLI_DIR/target/classes"
    API_CLASSES="$ROOT_DIR/att-server-api/target/classes"; REMOTE_CLASSES="$ROOT_DIR/att-remote/target/classes"
    ENGINE_SOURCES="$ENGINE_DIR/target/sources.list"; CLI_SOURCES="$CLI_DIR/target/sources.list"
    API_SOURCES="$ROOT_DIR/att-server-api/target/sources.list"; REMOTE_SOURCES="$ROOT_DIR/att-remote/target/sources.list"
    mkdir -p "$ENGINE_CLASSES" "$CLI_CLASSES" "$API_CLASSES" "$REMOTE_CLASSES"
    find "$ENGINE_DIR/src/main/java" -name '*.java' > "$ENGINE_SOURCES"
    if [ ! -s "$ENGINE_SOURCES" ]; then echo "No engine Java sources found to compile." >&2; exit 2; fi
    javac -source 8 -target 8 -cp "$CP" -d "$ENGINE_CLASSES" @"$ENGINE_SOURCES"
    if [ -d "$ENGINE_DIR/src/main/resources" ]; then cp -R "$ENGINE_DIR/src/main/resources/." "$ENGINE_CLASSES/"; fi
    if [ -f "$ENGINE_CLASSES/att-build.properties" ]; then
      PROJECT_VERSION=$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$ROOT_DIR/pom.xml" | head -n 1)
      GIT_COMMIT=$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)
      BUILD_TIME=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
      sed -e "s/\${project.version}/$PROJECT_VERSION/g" -e "s/\${maven.build.timestamp}/$BUILD_TIME/g" -e "s/\${att.gitCommit}/$GIT_COMMIT/g" \
        "$ENGINE_CLASSES/att-build.properties" > "$ENGINE_CLASSES/att-build.properties.tmp"
      mv "$ENGINE_CLASSES/att-build.properties.tmp" "$ENGINE_CLASSES/att-build.properties"
    fi
    touch "$ENGINE_BUILD_MARKER"
    find "$ROOT_DIR/att-server-api/src/main/java" -name '*.java' > "$API_SOURCES"
    javac -source 8 -target 8 -cp "$CP" -d "$API_CLASSES" @"$API_SOURCES"
    find "$ROOT_DIR/att-remote/src/main/java" -name '*.java' > "$REMOTE_SOURCES"
    javac -source 8 -target 8 -cp "$API_CLASSES:$CP" -d "$REMOTE_CLASSES" @"$REMOTE_SOURCES"
    touch "$API_BUILD_MARKER" "$REMOTE_BUILD_MARKER"
    cp "$ENGINE_BUILD_MARKER" "$BUILD_MARKER"
    find "$CLI_DIR/src/main/java" -name '*.java' > "$CLI_SOURCES"
    if [ ! -s "$CLI_SOURCES" ]; then echo "No CLI Java sources found to compile." >&2; exit 2; fi
    javac -source 8 -target 8 -cp "$ENGINE_CLASSES:$API_CLASSES:$REMOTE_CLASSES:$CP" -d "$CLI_CLASSES" @"$CLI_SOURCES"
    touch "$BUILD_MARKER"
  else echo "Neither Maven nor javac is available. Build a release package first." >&2; exit 2; fi
fi
exec java -cp "$CP" att.FrameworkRunner "$@"
