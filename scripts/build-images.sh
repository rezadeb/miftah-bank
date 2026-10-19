#!/usr/bin/env bash
# Build 3 image aplikasi (backend, frontend, load-balancer).
#
# Pemakaian:
#   ./scripts/build-images.sh                 # build saja
#   ./scripts/build-images.sh --import-k3s    # build + import ke containerd K3s (tanpa registry)
#   TAG=1.0.1 ./scripts/build-images.sh --import-k3s
set -euo pipefail

TAG="${TAG:-1.0.0}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMPORT_K3S=false
[[ "${1:-}" == "--import-k3s" ]] && IMPORT_K3S=true

declare -A CONTEXTS=(
  [backend]="$ROOT/backend"
  [frontend]="$ROOT/frontend"
  [load-balancer]="$ROOT/loadbalancer"
)

for name in backend frontend load-balancer; do
  image="miftah-bank/${name}:${TAG}"
  echo ">>> Build ${image}"
  docker build -t "${image}" "${CONTEXTS[$name]}"
  if $IMPORT_K3S; then
    echo ">>> Import ${image} ke K3s (containerd)"
    docker save "${image}" | sudo k3s ctr images import -
  fi
done

echo "Selesai. Image: miftah-bank/{backend,frontend,load-balancer}:${TAG}"
