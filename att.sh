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
REMOTE_DIR="$ROOT_DIR/att-remote"
API_DIR="$ROOT_DIR/att-server-api"
MISSING_CLASSES=""
for required_class in \
  "$CLI_DIR/target/classes/att/FrameworkRunner.class" \
  "$ENGINE_DIR/target/classes/att/Version.class" \
  "$REMOTE_DIR/target/classes/att/remote/RemoteCommand.class" \
  "$API_DIR/target/classes/att/server/api/ServerApi.class"; do
  if [ ! -f "$required_class" ]; then MISSING_CLASSES="$MISSING_CLASSES
  $required_class"; fi
done
if [ -n "$MISSING_CLASSES" ]; then
  echo "ATT source checkout is missing required prebuilt classes:$MISSING_CLASSES" >&2
  echo "Build explicitly with: mvn -DskipTests -pl att-cli -am compile" >&2
  echo "Or use the local package produced by the release build." >&2
  exit 2
fi
CP="$CLI_DIR/target/classes:$ENGINE_DIR/target/classes:$REMOTE_DIR/target/classes:$API_DIR/target/classes"
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
exec java -cp "$CP" att.FrameworkRunner "$@"
