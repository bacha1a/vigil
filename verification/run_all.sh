#!/usr/bin/env bash
# Runs the whole verification suite and prints a PASS/FAIL summary.
#   ./run_all.sh                  fast scenarios (00-04), ~5-6 min
#   ./run_all.sh --with-benchmark also runs the long head-to-head (05), +12-15 min
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

LOG="$RESULTS_DIR/run-$(date '+%Y%m%d-%H%M%S').log"
SCENARIOS=(00_environment 01_test_suite 07_tla_check 02_mutual_exclusion 03_crash_recovery 04_scheduler_survives_steal 06_mttr)
[ "${1:-}" = "--with-benchmark" ] && SCENARIOS+=(05_shedlock_benchmark)

echo "${B}Vigil verification suite${X}  ·  $(date)  ·  log → $LOG" | tee "$LOG"
declare -a SUMMARY
for s in "${SCENARIOS[@]}"; do
  out=$(bash "$HERE/$s.sh" 2>&1); echo "$out" | tee -a "$LOG"
  if echo "$out" | grep -q "X FAIL"; then SUMMARY+=("$R✗ FAIL$X  $s"); else SUMMARY+=("$G✔ PASS$X  $s"); fi
done

python3 "$HERE/gen_report.py" >/dev/null 2>&1 && echo "  visual report → $RESULTS_DIR/report.html"

banner "SUMMARY"
for r in "${SUMMARY[@]}"; do echo "  $r"; done | tee -a "$LOG"
echo; echo "  full log: $LOG  ·  dashboard: $RESULTS_DIR/report.html"
echo "${SUMMARY[*]}" | grep -q "FAIL" && exit 1 || exit 0
