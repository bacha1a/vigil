#!/usr/bin/env bash
# Prints the test environment and cluster status. `--up` starts the cluster, `--down` stops it.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

case "${1:-}" in
  --up)
    step "Building images and starting the 6-pod head-to-head cluster…"
    ( cd "$ROOT/vigil-demo" && mvn -o -q package -DskipTests -Djacoco.skip=true 2>/dev/null ) || true
    ( cd "$ROOT/vigil-shedlock-bench" && mvn -o -q package -DskipTests -Djacoco.skip=true 2>/dev/null ) || true
    docker build -q -t vigil-demo:latest "$ROOT/vigil-demo" >/dev/null
    docker build -q -t shedlock-bench:latest "$ROOT/vigil-shedlock-bench" >/dev/null
    dc up -d postgres-vigil postgres-shedlock vigil-app-1 vigil-app-2 vigil-app-3 shedlock-app-1 shedlock-app-2 shedlock-app-3
    info "waiting for apps…"; sleep 25
    ;;
  --down)
    step "Stopping cluster…"; dc down -v; exit 0 ;;
esac

banner "ENVIRONMENT"
step "Host"
info "OS        : $(uname -srm)"
info "Java      : $(java -version 2>&1 | head -1)"
info "Maven     : $(mvn -v 2>/dev/null | head -1)"
info "Docker    : $(docker --version)"

step "System under test"
info "Vigil cluster   : 3 pods (vigil-pod-1/2/3) → PostgreSQL 16  [ports 8081-8083]"
info "ShedLock cluster: 3 pods (shedlock-pod-1/2/3) → PostgreSQL 16  [ports 8084-8086]"
info "Same job (monthly-billing, 45 customers), same fake Stripe writing a shared billing_audit table."
info "Automated test suites use Testcontainers (PostgreSQL 16, Redis 7)."

step "Cluster status"
if cluster_up; then
  dc ps --format '  {{.Name}}\t{{.Service}}\t{{.Status}}' 2>/dev/null | sort | sed "s/^/${D}/;s/$/${X}/"
  info "vigil billing_audit rows=$(charges vigil)  ·  shedlock billing_audit rows=$(charges shedlock)"
  pass "cluster is UP"
else
  fail "cluster is DOWN - run:  verification/00_environment.sh --up"
fi
