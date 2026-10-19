#!/usr/bin/env bash
# Deploy / update aplikasi ke cluster (namespace miftah-bank).
# Pastikan image sudah ada di node K3s (lihat scripts/build-images.sh --import-k3s).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NS=miftah-bank

kubectl apply -k "$ROOT/k8s"

echo ">>> Menunggu semua deployment siap..."
for d in redis postgres backend frontend load-balancer; do
  kubectl -n "$NS" rollout status "deployment/$d" --timeout=300s
done

echo
kubectl -n "$NS" get pods,svc,ingress
echo
echo "Buka aplikasi: http://<IP-node-K3s>/   (login: demo / demo123)"
