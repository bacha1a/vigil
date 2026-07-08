#!/usr/bin/env bash
set -euo pipefail

DURATION_S="${1:-45}"
WORK_MS="${2:-120}"
LEASE_MS="${3:-800}"
STOP_EVERY_S="${4:-3}"
STOP_FOR_S="${5:-2}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
JAVA="$JAVA_HOME/bin/java"
PG="vigil-soak-pg"
PGPORT="5544"
URL="jdbc:postgresql://localhost:${PGPORT}/soak?user=postgres&password=pass"
DURATION_MS=$((DURATION_S * 1000))
CPFILE="$(mktemp)"

echo "== starting postgres =="
docker rm -f "$PG" >/dev/null 2>&1 || true
docker run -d --name "$PG" -e POSTGRES_PASSWORD=pass -e POSTGRES_DB=soak -p "${PGPORT}:5432" postgres:16 >/dev/null
until docker exec "$PG" pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done
sleep 2

echo "== compiling harness =="
mvn -q -pl vigil-scheduler -am test-compile -Djacoco.skip=true
mvn -q -pl vigil-scheduler dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$CPFILE" >/dev/null
CP="vigil-scheduler/target/test-classes:vigil-scheduler/target/classes:vigil-jdbc/target/classes:vigil-core/target/classes:$(cat "$CPFILE")"

run_lib() {
  local lib="$1"
  echo "== ${lib}: init schema =="
  "$JAVA" -cp "$CP" io.vigil.verify.PodRunner init "$URL"

  echo "== ${lib}: launching pods for ${DURATION_S}s =="
  "$JAVA" -cp "$CP" io.vigil.verify.PodRunner run "$lib" pod-1 "$URL" job "$DURATION_MS" "$WORK_MS" "$LEASE_MS" &
  local P1=$!
  "$JAVA" -cp "$CP" io.vigil.verify.PodRunner run "$lib" pod-2 "$URL" job "$DURATION_MS" "$WORK_MS" "$LEASE_MS" &
  local P2=$!
  "$JAVA" -cp "$CP" io.vigil.verify.PodRunner run "$lib" pod-3 "$URL" job "$DURATION_MS" "$WORK_MS" "$LEASE_MS" &
  local P3=$!

  (
    local deadline=$((SECONDS + DURATION_S))
    while [ $SECONDS -lt $deadline ]; do
      sleep "$STOP_EVERY_S"
      kill -STOP "$P1" 2>/dev/null || true
      sleep "$STOP_FOR_S"
      kill -CONT "$P1" 2>/dev/null || true
    done
  ) &
  local FAULT=$!

  wait "$P1" "$P2" "$P3" 2>/dev/null || true
  kill -CONT "$P1" 2>/dev/null || true
  kill "$FAULT" 2>/dev/null || true

  echo -n "== ${lib}: result == "
  "$JAVA" -cp "$CP" io.vigil.verify.PodRunner reconcile "$URL" job "$lib"
}

run_lib vigil
run_lib shedlock

echo "== cleanup =="
docker rm -f "$PG" >/dev/null 2>&1 || true
rm -f "$CPFILE"
