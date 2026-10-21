#!/usr/bin/env bash
# Deploy (or update) the app in namespace "miftah-bank".
# The images must already exist on the K3s node (see scripts/build-images.sh --import-k3s).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NS=miftah-bank
# Default: images imported into K3s. For Docker Hub images use:
#   K8S_DIR=k8s-dockerhub ./scripts/deploy.sh
K8S_DIR="${K8S_DIR:-k8s}"

kubectl apply -k "$ROOT/$K8S_DIR"

echo ">>> Waiting for all deployments to be ready..."
for d in redis postgres backend frontend load-balancer; do
  kubectl -n "$NS" rollout status "deployment/$d" --timeout=300s
done

echo
kubectl -n "$NS" get pods,svc,ingress
echo
echo "Open the app: http://<K3s-node-IP>/   (login: demo / demo123)"
