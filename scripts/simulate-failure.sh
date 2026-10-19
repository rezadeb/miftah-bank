#!/usr/bin/env bash
# Simulasi pod mati / hidup lagi untuk demo Dynatrace (scale deployment ke 0 / 1).
#
# Pemakaian:
#   ./scripts/simulate-failure.sh down <komponen>
#   ./scripts/simulate-failure.sh up   <komponen>
#   ./scripts/simulate-failure.sh status
#
# Komponen: load-balancer | frontend | backend | redis | postgres | all
set -euo pipefail

NS="${NAMESPACE:-miftah-bank}"
COMPONENTS=(load-balancer frontend backend redis postgres)

usage() { sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

expected() {
  case "$1" in
    load-balancer) echo "Ingress/Traefik membalas HTTP 503 Service Unavailable (tidak ada pod nginx)";;
    frontend)      echo "nginx membalas HTTP 503 (halaman 'Service Unavailable' / JSON FRONTEND_UNAVAILABLE)";;
    backend)       echo "Frontend membalas HTTP 503 'Transaction failed' / 'Login failed' (BACKEND_UNAVAILABLE)";;
    redis)         echo "Login gagal: HTTP 503 REDIS_UNAVAILABLE (user yang sudah login masih bisa transaksi)";;
    postgres)      echo "Cek saldo/transfer/riwayat gagal: HTTP 503 DB_UNAVAILABLE (login masih jalan)";;
  esac
}

scale() {  # scale <komponen> <replicas>
  kubectl -n "$NS" scale "deployment/$1" --replicas="$2"
}

action="${1:-}"; target="${2:-}"
case "$action" in
  status)
    kubectl -n "$NS" get deployments,pods -o wide
    ;;
  down|up)
    [[ -z "$target" ]] && usage
    replicas=$([[ "$action" == "down" ]] && echo 0 || echo 1)
    if [[ "$target" == "all" ]]; then
      for c in "${COMPONENTS[@]}"; do scale "$c" "$replicas"; done
    else
      [[ " ${COMPONENTS[*]} " == *" $target "* ]] || { echo "Komponen tidak dikenal: $target"; usage; }
      scale "$target" "$replicas"
      [[ "$action" == "down" ]] && echo "Yang diharapkan terlihat: $(expected "$target")"
    fi
    ;;
  *) usage;;
esac
