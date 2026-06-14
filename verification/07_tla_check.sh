#!/usr/bin/env bash
# Formal verification: runs TLC against the TLA+ spec that lives WITH the library
# (vigil-core/src/main/tla/VigilLock.tla). The spec is intentionally NOT moved here -
# this script only *runs* it and records the result into the same verification dashboard.
# No docker cluster needed.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

skip(){ echo "${Y}${B}  ⊘ SKIP${X} - $*" >&2; }   # distinct from "X FAIL" so run_all won't mark it failed

TLA_DIR="$ROOT/vigil-core/src/main/tla"
SPEC="VigilLock.tla"
CFG="VigilLock.cfg"

banner "7 · FORMAL VERIFICATION - TLA+ / TLC"
info "Spec: vigil-core/src/main/tla/$SPEC  (colocated with the library; run in place)."
info "Properties: MutualExclusion · NoZombieWrite · TokenMonotonicity · StolenRunMarked · EventualProgress (N=3)."

[ -f "$TLA_DIR/$SPEC" ] || { skip "spec not found at $TLA_DIR/$SPEC"; exit 0; }
command -v java >/dev/null || { skip "java not found on PATH"; exit 0; }

# Locate tla2tools.jar: env var, local cache, common paths; else try a one-time download.
JAR=""
for cand in "${TLA2TOOLS_JAR:-}" "$HERE/.tools/tla2tools.jar" "$HOME/tla2tools.jar" /opt/tlaplus/tla2tools.jar; do
  [ -n "$cand" ] && [ -f "$cand" ] && { JAR="$cand"; break; }
done
if [ -z "$JAR" ]; then
  step "tla2tools.jar not found - attempting one-time download (needs network)…"
  mkdir -p "$HERE/.tools"
  if command -v curl >/dev/null && \
     curl -fsSL -o "$HERE/.tools/tla2tools.jar" \
       "https://github.com/tlaplus/tlaplus/releases/latest/download/tla2tools.jar" 2>/dev/null; then
    JAR="$HERE/.tools/tla2tools.jar"
  fi
fi
if [ -z "$JAR" ]; then
  skip "tla2tools.jar unavailable (offline?). Install once, then re-run:"
  info "  curl -L -o verification/.tools/tla2tools.jar https://github.com/tlaplus/tlaplus/releases/latest/download/tla2tools.jar"
  info "  (or export TLA2TOOLS_JAR=/path/to/tla2tools.jar)"
  info "Recorded thesis run for reference: 1217 distinct states, 0 violations, 5 properties."
  exit 0
fi

step "Running TLC  (java -cp tla2tools.jar tlc2.TLC -config $CFG $SPEC)…"
LOG="$RESULTS_DIR/07_tla.out"
( cd "$TLA_DIR" && java -XX:+UseParallelGC -cp "$JAR" tlc2.TLC -workers auto -config "$CFG" "$SPEC" ) > "$LOG" 2>&1

states=$(grep -oiE "[0-9]+ distinct states" "$LOG" | tail -1 | grep -oE "^[0-9]+")
if grep -qiE "No error has been found|Model checking completed" "$LOG" && ! grep -qiE "is violated|^Error:|Errors:" "$LOG"; then
  ST=PASS
else
  ST=FAIL
fi

echo "  ${B}distinct states explored:${X} ${states:-?}    ${B}status:${X} $ST"
record_result tla_check "Formal verification (TLA+ / TLC)" "$ST" \
  "\"distinct_states\":${states:-0},\"properties\":5,\"pods\":3"
[ "$ST" = PASS ] && pass "TLC explored ${states:-?} distinct states - no property violations" \
                 || fail "TLC reported a violation - see $LOG"
