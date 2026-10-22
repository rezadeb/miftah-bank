#!/usr/bin/env bash
# Build the application images and (optionally) load them into K3s.
# The 4th image, load-balancer-apache, is for the Apache experiment (see README, section 8).
# No Docker Hub or other registry is needed.
#
# Usage:
#   ./scripts/build-images.sh                  # build only
#   ./scripts/build-images.sh --import-k3s     # build + import into K3s (containerd)
#   TAG=2.0.0 ./scripts/build-images.sh --import-k3s     # one tag for all images
#
# Run this on the K3s node (miftah-ubuntu). Docker must be installed there.
set -euo pipefail

# Default tag per image. Set TAG=x.y.z to use one tag for all images.
declare -A TAGS=([backend]="1.0.0" [frontend]="1.0.0" [load-balancer]="1.0.2" [load-balancer-apache]="1.0.0")
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMPORT_K3S=false
[[ "${1:-}" == "--import-k3s" ]] && IMPORT_K3S=true

command -v docker >/dev/null || { echo "Docker is not installed. Install it first (for example: sudo apt install docker.io)."; exit 1; }

declare -A CONTEXTS=(
  [backend]="$ROOT/backend"
  [frontend]="$ROOT/frontend"
  [load-balancer]="$ROOT/loadbalancer"
  [load-balancer-apache]="$ROOT/loadbalancer-apache"
)

for name in backend frontend load-balancer load-balancer-apache; do
  image="miftah-bank/${name}:${TAG:-${TAGS[$name]}}"
  echo ">>> Building ${image}"
  docker build -t "${image}" "${CONTEXTS[$name]}"
  if $IMPORT_K3S; then
    echo ">>> Importing ${image} into K3s"
    # K3s keeps its images in the "k8s.io" containerd namespace.
    docker save "${image}" | sudo k3s ctr -n k8s.io images import -
  fi
done

echo "Done. Images: miftah-bank/backend:${TAG:-${TAGS[backend]}}, frontend:${TAG:-${TAGS[frontend]}}, load-balancer:${TAG:-${TAGS[load-balancer]}}, load-balancer-apache:${TAG:-${TAGS[load-balancer-apache]}}"
$IMPORT_K3S || echo "Tip: add --import-k3s so Kubernetes can find the images."
