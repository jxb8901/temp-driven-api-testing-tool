#!/usr/bin/env sh
# Author: Jeffrey + ChatGPT
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$ROOT_DIR"

APP_JAR=""
for candidate in "$ROOT_DIR"/lib/att-*.jar; do
  if [ -f "$candidate" ]; then APP_JAR="$candidate"; break; fi
done
if [ -n "$APP_JAR" ]; then
  CP="$APP_JAR"
  for jar in "$ROOT_DIR"/lib/*.jar; do
    [ -f "$jar" ] || continue
    [ "$jar" = "$APP_JAR" ] && continue
    CP="$CP:$jar"
  done
  exec java -cp "$CP" att.FrameworkRunner "$@"
fi

CP="$ROOT_DIR/target/classes:$ROOT_DIR/target/test-classes"

for jar in "$HOME"/.m2/repository/commons-io/commons-io/2.16.1/commons-io-2.16.1.jar \
  "$HOME"/.m2/repository/com/fasterxml/jackson/core/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar \
  "$HOME"/.m2/repository/com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar \
  "$HOME"/.m2/repository/com/fasterxml/jackson/core/jackson-databind/2.17.2/jackson-databind-2.17.2.jar \
  "$HOME"/.m2/repository/com/networknt/json-schema-validator/1.4.0/json-schema-validator-1.4.0.jar \
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
  "$HOME"/.m2/repository/org/apache/logging/log4j/log4j-api/2.21.1/log4j-api-2.21.1.jar; do
  CP="$CP:$jar"
done

NEEDS_BUILD=false
BUILD_MARKER="$ROOT_DIR/target/classes/att-build.properties"
if [ ! -f "$BUILD_MARKER" ]; then
  NEEDS_BUILD=true
elif find "$ROOT_DIR/src/main/java" -name '*.java' -newer "$BUILD_MARKER" -print -quit | grep -q .; then
  NEEDS_BUILD=true
fi

if [ "$NEEDS_BUILD" = true ]; then
  echo "Compiling ATT sources..."
  if command -v mvn >/dev/null 2>&1; then
    (cd "$ROOT_DIR" && mvn -q -DskipTests compile)
  elif command -v javac >/dev/null 2>&1; then
    mkdir -p "$ROOT_DIR/target/classes"
    # shellcheck disable=SC2046
    javac -source 8 -target 8 -cp "$CP" -d "$ROOT_DIR/target/classes" $(find "$ROOT_DIR/src/main/java" -name '*.java')
  else
    echo "Neither Maven nor javac is available. Build a release package first." >&2
    exit 2
  fi
fi

exec java -cp "$CP" att.FrameworkRunner "$@"
