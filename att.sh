#!/usr/bin/env sh
# Author: Jeffrey + ChatGPT
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$ROOT_DIR"

if [ -d "$ROOT_DIR/classes" ]; then
  CP="$ROOT_DIR/classes"
  for jar in "$ROOT_DIR"/lib/*.jar; do
    [ -f "$jar" ] || continue
    CP="$CP:$jar"
  done
  exec java -cp "$CP" com.company.apitest.FrameworkRunner "$@"
fi

if [ ! -d "$ROOT_DIR/target/classes" ]; then
  if command -v mvn >/dev/null 2>&1; then
    (cd "$ROOT_DIR" && mvn -q -DskipTests compile)
  else
    echo "target/classes not found. Build classes first, or run scripts/build-release.sh to create a release package." >&2
    exit 2
  fi
fi

CP="$ROOT_DIR/target/classes:$ROOT_DIR/target/test-classes"

for jar in "$HOME"/.m2/repository/commons-io/commons-io/2.16.1/commons-io-2.16.1.jar \
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
  "$HOME"/.m2/repository/org/apache/logging/log4j/log4j-api/2.21.1/log4j-api-2.21.1.jar \
  "$HOME"/.m2/repository/org/apache/logging/log4j/log4j-core/2.21.1/log4j-core-2.21.1.jar; do
  CP="$CP:$jar"
done

exec java -cp "$CP" com.company.apitest.FrameworkRunner "$@"
