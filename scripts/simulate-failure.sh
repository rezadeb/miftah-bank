#!/usr/bin/env bash
# Simulate a component failure for the Dynatrace demo.
#
# Usage:
#   ./scripts/simulate-failure.sh down   <component>   # scale the deployment to 0 (same as: kubectl scale --replicas=0)
#   ./scripts/simulate-failure.sh up     <component>   # scale the deployment back to 1
#   ./scripts/simulate-failure.sh kill   <component>   # delete the pod; Kubernetes starts a new one (a short outage + restart)
#   ./scripts/simulate-failure.sh status               # show deployments and pods
#
# Components: load-balancer | frontend | backend | redis | postgres | all
set -euo pipefail

NS="${NAMESPACE:-miftah-bank}"
COMPONENTS=(load-balancer frontend backend redis postgres)

usage() { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

expected() {
  case "$1" in
    load-balancer) echo "The Ingress (Traefik) answers HTTP 503, because no nginx pod is running.";;
    frontend)      echo "nginx answers HTTP 503 (Service Unavailable page, or JSON FRONTEND_UNAVAILABLE).";;
    backend)       echo "The frontend answers HTTP 503: 'Transaction failed' or 'Login failed' (BACKEND_UNAVAILABLE).";;
    redis)         echo "Login fails with HTTP 503 REDIS_UNAVAILABLE. Users who are already logged in can still use the app.";;
    postgres)      echo "Balance, transfer and history fail with HTTP 503 DB_UNAVAILABLE. Login still works.";;
  esac
}

check_component() {
  [[ " ${COMPONENTS[*]} " == *" $1 "* ]] || { echo "Unknown component: $1"; usage; }
}

each_target() {  # each_target <command> <component|all>
  local cmd="$1" target="$2"
  if [[ "$target" == "all" ]]; then
    for c in "${COMPONENTS[@]}"; do "$cmd" "$c"; done
  else
    check_component "$target"
    "$cmd" "$target"
  fi
}

scale_down() { kubectl -n "$NS" scale "deployment/$1" --replicas=0; }
scale_up()   { kubectl -n "$NS" scale "deployment/$1" --replicas=1; }
kill_pod()   { kubectl -n "$NS" delete pod -l "app.kubernetes.io/name=$1" --wait=false; }

action="${1:-}"; target="${2:-}"
case "$action" in
  status) kubectl -n "$NS" get deployments,pods -o wide ;;
  down)   [[ -n "$target" ]] || usage; each_target scale_down "$target"
          [[ "$target" == "all" ]] || echo "What you should see: $(expected "$target")" ;;
  up)     [[ -n "$target" ]] || usage; each_target scale_up "$target" ;;
  kill)   [[ -n "$target" ]] || usage; each_target kill_pod "$target" ;;
  *)      usage ;;
esac
