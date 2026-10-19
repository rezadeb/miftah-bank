#!/usr/bin/env bash
# Load generator sederhana: login -> cek saldo -> transfer -> riwayat, diulang terus.
# Berguna supaya Dynatrace punya trafik (service flow, PurePath, failure rate) saat demo.
#
# Pemakaian:
#   BASE_URL=http://<IP-node-K3s> ./scripts/load-generator.sh
#   BASE_URL=http://<IP> INTERVAL=0.5 DURATION=300 ./scripts/load-generator.sh
#
# Variabel: BASE_URL (default http://localhost) | INTERVAL detik antar iterasi (default 1)
#           DURATION detik total (default 0 = tanpa batas, hentikan dengan Ctrl+C)
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost}"
INTERVAL="${INTERVAL:-1}"
DURATION="${DURATION:-0}"
USERS=("demo:demo123" "miftah:miftah123" "alice:alice123")
AMOUNTS=(10000 25000 50000 100000 250000)
JAR="$(mktemp)"
iter=0; fail=0; start=$(date +%s)

summary() {
  rm -f "$JAR"
  echo
  echo "Selesai. Total iterasi: $iter, request gagal (non-2xx): $fail"
}
trap 'summary; exit 0' INT TERM

call() {  # call <label> <curl args...>  -> cetak status, hitung gagal
  local label="$1"; shift
  local code
  code=$(curl -s -o /dev/null -m 10 -w '%{http_code}' -b "$JAR" -c "$JAR" "$@")
  [[ "$code" =~ ^2 ]] || fail=$((fail + 1))
  printf '%s=%s ' "$label" "$code"
}

echo "Load generator -> $BASE_URL (Ctrl+C untuk berhenti)"
while :; do
  iter=$((iter + 1))
  pair="${USERS[RANDOM % ${#USERS[@]}]}"; user="${pair%%:*}"; pass="${pair##*:}"
  amount="${AMOUNTS[RANDOM % ${#AMOUNTS[@]}]}"
  : > "$JAR"

  printf '#%-4s %-7s ' "$iter" "$user"
  call login    -X POST "$BASE_URL/api/login" -H 'Content-Type: application/json' \
                -d "{\"username\":\"$user\",\"password\":\"$pass\"}"
  call saldo    "$BASE_URL/api/balance"
  call transfer -X POST "$BASE_URL/api/transfer" -H 'Content-Type: application/json' \
                -d "{\"amount\":$amount}"
  call riwayat  "$BASE_URL/api/transactions"
  echo

  if (( DURATION > 0 && $(date +%s) - start >= DURATION )); then break; fi
  sleep "$INTERVAL"
done

summary
