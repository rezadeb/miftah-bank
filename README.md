# Miftah Bank

A small **dummy banking app** for **Dynatrace OneAgent demos** on Kubernetes (K3s).
It is simple on purpose, but it has a full service chain and a failure scenario for every component.
This makes it easy to show Service Flow, distributed traces (PurePath), logs, Kubernetes, RUM and Davis Problems.

> **For lab demos only.** Passwords are stored as SHA-256 in Redis, secrets are in git, and the login token is not a standard JWT. Do not use this in production.

**Contents:** [Architecture](#1-architecture) · [Features](#2-features) · [Repo layout](#3-repo-layout) · [Deploy to K3s](#4-deploy-to-k3s) · [Load generator (Locust)](#5-load-generator-locust) · [Failure scenarios](#6-failure-scenarios) · [What to show in Dynatrace](#7-what-to-show-in-dynatrace) · [OneAgent notes](#8-oneagent-compatibility-notes) · [API](#9-api) · [Run locally](#10-run-locally-without-kubernetes) · [Troubleshooting](#11-troubleshooting) · [Roadmap](#12-roadmap)

---

## 1. Architecture

```
 Browser (web)                                    [outside the cluster]
      |  HTTP :80
 Ingress (Traefik, built into K3s)
 ======|=========================== namespace: miftah-bank ===========
      v  HTTP :80 -> :8080
 load-balancer   nginx 1.30.4
      |  HTTP :3000
      v
 frontend        Node.js 24 LTS (Express)    web pages + BFF /api/*
      |  HTTP :8081
      v
 backend         Java 25 LTS (Spring Boot 4.1)
      |  RESP :6379                 |  JDBC :5432
      v                             v
 redis 7.4  (login check)        postgres 17  (balance + transactions)
 ===================================================================
```

| Component | Technology | Port | Job |
|---|---|---|---|
| `load-balancer` | nginx 1.30.4 (Debian) | 8080 (Service: 80) | Reverse proxy to the frontend. Answers 503 by itself when the frontend is down. |
| `frontend` | Node.js 24 LTS + Express 5 | 3000 | Login and Home pages. Forwards `/api/*` to the backend (BFF). |
| `backend` | Java 25 LTS + Spring Boot 4.1.1 | 8081 | Business logic: login against Redis, balance and transfer in PostgreSQL. |
| `redis` | Redis 7.4 | 6379 | User data for **login**. |
| `postgres` | PostgreSQL 17 | 5432 | **Balance** and **transaction** history. |

Everything runs in one namespace: **`miftah-bank`** (5 deployments).

### Design choices

- **Separate failure domains.** The login token is stateless (HMAC), so Redis is only used at *login*. If Redis is down, users who are already logged in can still check balance and transfer. If Postgres is down, login still works.
- **Probes do not check dependencies.** If a database is down, the backend pod stays *Ready* and answers HTTP 503 per request. It is not restarted, so the demo behaves the same every time.
- **Fast, clear errors.** Redis and Postgres connection timeouts are about 2 seconds. Every error has a clear code: `REDIS_UNAVAILABLE`, `DB_UNAVAILABLE`, `BACKEND_UNAVAILABLE`, `FRONTEND_UNAVAILABLE`.
- **Debian/Ubuntu (glibc) images, not Alpine**, so OneAgent injection works smoothly.
- **nginx → frontend line in the Map: known gap.** OneAgent links the trace (same trace ID), but it does not record an outgoing call from nginx, so the Map has no `load-balancer → frontend` line. A static `proxy_pass` did not change this. An [Apache httpd variant](#experiment-apache-httpd-as-the-load-balancer) is available to test other load balancers.

---

## 2. Features

- **Login** with username and password, checked against **Redis**.
- **Check Balance**: reads the balance from **PostgreSQL** and shows it in Rupiah.
- **Transfer Money**: enter an amount. The balance **always goes up** by that amount, and the transfer is saved in the history (**date, time, amount**, and balance after).
- **Transaction History**: the latest 20 transactions, shown in WIB time.

**Demo accounts** (created by the backend at start-up, safe to repeat):

| Username | Password | Starting balance |
|---|---|---|
| `demo` | `demo123` | Rp 1.000.000 |
| `miftah` | `miftah123` | Rp 5.000.000 |
| `alice` | `alice123` | Rp 2.500.000 |

---

## 3. Repo layout

```
backend/        Spring Boot (Java 25) + Dockerfile + unit tests
frontend/       Node.js 24 (Express) + views/ + public/ + Dockerfile
loadbalancer/   nginx (config template, 503 error pages) + Dockerfile
loadbalancer-apache/   Apache httpd variant of the load balancer (experiment) + Dockerfile
k8s/            Kubernetes manifests (kustomize), namespace miftah-bank
k8s-dockerhub/  Same manifests, images from Docker Hub
k8s-apache/     Same as k8s-dockerhub, but with Apache instead of nginx (experiment)
loadgen/        Locust load generator (locustfile + Kubernetes manifests)
dynatrace/      Example DynaKube (cloud native full stack)
scripts/        build-images.sh, deploy.sh, simulate-failure.sh
```

---

## 4. Deploy to K3s

**You need:** a running K3s (with Traefik), `kubectl`, a Dynatrace SaaS tenant, and **Docker on the K3s node** if you build the images there (see [4.2](#42-get-the-images-onto-k3s)).

### 4.1 Install Dynatrace Operator and DynaKube

Follow the official guide: [Get started with full observability (cloud native full stack)](https://docs.dynatrace.com/docs/ingest-from/setup-on-k8s/installation/cloud-native-fullstack). In short (checked on 2026-10-19):

```bash
helm install dynatrace-operator oci://public.ecr.aws/dynatrace/dynatrace-operator \
  --create-namespace --namespace dynatrace --atomic    # Helm 4: use --rollback-on-failure instead of --atomic

kubectl -n dynatrace create secret generic dynakube \
  --from-literal="apiToken=<OPERATOR_TOKEN>" \
  --from-literal="dataIngestToken=<DATA_INGEST_TOKEN>"

kubectl apply -f dynatrace/dynakube.yaml   # set apiUrl, and match apiVersion to your Operator version
```

The token scopes are listed on the *Tokens and permissions required* page of the guide.
By default OneAgent is injected into all namespaces, including `miftah-bank`.

### 4.2 Get the images onto K3s

**You do not need Docker Hub.** K3s uses containerd, and it can use images that are already on the node.
The manifests use `imagePullPolicy: IfNotPresent`, so Kubernetes will not try to pull `miftah-bank/*` from the internet.

| Option | How | When to use |
|---|---|---|
| **A. Build on the node + import** (recommended) | `./scripts/build-images.sh --import-k3s` | Single-node lab like `miftah-ubuntu`. Simplest. Needs Docker on the node. |
| **B. Image tar files** | `docker save` the image to a `.tar` file in `/var/lib/rancher/k3s/agent/images/`. K3s imports it when it starts. | Build on another machine, copy the files, no registry. |
| **C. A registry** (GHCR, Docker Hub, local registry) | Push the images, then change the names in `k8s-dockerhub/kustomization.yaml` and deploy with `K8S_DIR=k8s-dockerhub ./scripts/deploy.sh`. | Multi-node cluster, or you want CI to publish images. A private registry also needs an `imagePullSecret`. |

Notes:
- Building *inside* the cluster is possible, but it needs an extra tool. Kaniko is archived (June 2025), so it is not a good choice for a new setup.
- GHCR packages are **private by default**. Make the package public, or create an `imagePullSecret`.

### 4.3 Deploy

```bash
git clone https://github.com/rezadeb/miftah-bank.git && cd miftah-bank

./scripts/build-images.sh --import-k3s   # build 3 images and import them into K3s
./scripts/deploy.sh                      # kubectl apply -k k8s/ and wait for all rollouts
```

Open `http://<K3s-node-IP>/` and log in with `demo` / `demo123`.

> Safest order: install the DynaKube **before** `deploy.sh`, so the pods get OneAgent from the start.
> If the app was deployed first, run `kubectl -n miftah-bank rollout restart deployment`.

To remove everything: `kubectl delete -k k8s/` (this also deletes the data volumes).

---

## 5. Load generator (Locust)

[Locust](https://locust.io) creates traffic, so Dynatrace has data (Service Flow, PurePath, failure rate) during the demo.
**You control the traffic**: the number of users and the spawn rate can be changed live in the web UI.

Each virtual user logs in, then repeats: check balance, transfer money, view history, open the Home page.
If login fails (for example Redis is down), the user keeps trying to log in again. These failures show up in the Locust statistics.

### 5.1 Run in Kubernetes (recommended)

```bash
kubectl apply -k loadgen/
```

1. Open **`http://<K3s-node-IP>:30089`**.
2. Enter **Number of users** and **Ramp up** (users started per second), then click **Start**.
3. While the test runs, click **Edit** (top bar) to change the number of users at any time.

The target host is `http://traefik.kube-system.svc.cluster.local`, so traffic goes through Traefik like a real browser.
Check that the Service name is right with `kubectl -n kube-system get svc traefik`.
If it is different, type another host in the web UI, for example `http://load-balancer` (this goes straight to nginx and skips Traefik).

The Locust pod has the annotation `oneagent.dynatrace.com/inject: "false"`, so OneAgent is **not** injected into it.

Remove it with `kubectl delete -k loadgen/`.

### 5.2 Run on your laptop

```bash
pip install -r loadgen/requirements.txt          # Python 3.11 or newer
cd loadgen
locust -f locustfile.py --host http://<K3s-node-IP>            # web UI on http://localhost:8089
```

Without the web UI (good for scripts):

```bash
locust -f locustfile.py --host http://<K3s-node-IP> --headless -u 20 -r 2 -t 10m
#  -u users   -r users started per second   -t total run time
```

### 5.3 Settings

Set these as environment variables (in `loadgen/locust.yaml` for Kubernetes, or in your shell):

| Variable | Default | Meaning |
|---|---|---|
| `WAIT_MIN`, `WAIT_MAX` | `1`, `3` | Seconds a user waits between actions. Lower numbers = more requests per user. |
| `TRANSFER_MIN`, `TRANSFER_MAX` | `10000`, `250000` | Range of the transfer amount (Rp). |
| `BANK_USERS` | the 3 demo accounts | Accounts to use, like `demo:demo123,alice:alice123`. |

Rough traffic: one user with the default wait time makes about 0.5 requests per second.

### 5.4 Wave traffic (optional)

`locustfile_wave.py` makes the number of users go up and down by itself, like busy and quiet hours.
In `loadgen/locust.yaml`, change the argument to `/mnt/locust/locustfile_wave.py`, or locally run `locust -f locustfile_wave.py`.

| Variable | Default | Meaning |
|---|---|---|
| `WAVE_LOW`, `WAVE_HIGH` | `5`, `40` | Fewest and most users. |
| `WAVE_PERIOD` | `300` | Seconds for one full wave. |
| `WAVE_SPAWN_RATE` | `5` | Users started or stopped per second. |
| `WAVE_STOP_AFTER` | `0` | Stop after this many seconds (`0` = run until you stop it). |

In wave mode the users are controlled by the script, so the user fields in the web UI have no effect.

> After you edit a locustfile, run `kubectl apply -k loadgen/` and then `kubectl -n miftah-bank rollout restart deployment/locust`.

---

## 6. Failure scenarios

```bash
./scripts/simulate-failure.sh down <component>   # scale the deployment to 0 replicas
./scripts/simulate-failure.sh up   <component>   # start it again (1 replica)
./scripts/simulate-failure.sh kill <component>   # delete the pod; Kubernetes creates a new one
./scripts/simulate-failure.sh status
# component: load-balancer | frontend | backend | redis | postgres | all
```

- **`down` is the same as** `kubectl -n miftah-bank scale deployment/<component> --replicas=0`. The component stays down until you run `up`.
- **`kill`** deletes the pod. It is a short outage plus a restart, so you can show the pod restart in Dynatrace.

| Component down | What the user sees | Error code / source |
|---|---|---|
| `load-balancer` | HTTP **503 Service Unavailable** | From the Ingress (Traefik), because the Service has no endpoints |
| `frontend` | HTTP **503**, a "Service Unavailable" page (HTML) or JSON for `/api` | `FRONTEND_UNAVAILABLE`, answered by **nginx** |
| `backend` | HTTP **503** "Login failed" / "Transaction failed" | `BACKEND_UNAVAILABLE`, answered by the frontend |
| `redis` | Login fails, HTTP **503** | `REDIS_UNAVAILABLE`, from the backend |
| `postgres` | Check balance / transfer / history fail, HTTP **503** "Database error" | `DB_UNAVAILABLE`, from the backend |

For `load-balancer`, the error comes from the layer **in front of** nginx, because no nginx is running to answer.

Tip: start Locust first, then run `down` and `up` while the traffic is running. Dynatrace will show the failure rate and the Davis Problem.

---

## 7. What to show in Dynatrace

- **Smartscape / Service Flow**: `nginx → Node.js → Java → Redis / PostgreSQL`.
- **Distributed trace** of one "Transfer Money" click: frontend (Node.js) → backend (Java) → PostgreSQL query.
- **Automatic failure detection** when a pod is down: HTTP 5xx and the exceptions `REDIS_UNAVAILABLE` / `DB_UNAVAILABLE`, then a **Davis Problem**.
- **Kubernetes**: replica changes, pod restarts, events.
- **Logs**: application logs from stdout (frontend, backend, and the nginx access log with `rt=` and `upstream=`).
- **RUM** (web): user sessions and user actions (Login, Check Balance, Transfer Money).

Example DQL for error logs. Always limit the time range and use `limit` to keep query cost low:

```
fetch logs, from: now()-30m
| filter k8s.namespace.name == "miftah-bank"
| filter loglevel == "ERROR"
| limit 50
```

---

## 8. OneAgent compatibility notes

Checked against the official Dynatrace documentation on **2026-10-19** (target OneAgent **1.347**):

| Technology | Version used | OneAgent support |
|---|---|---|
| Node.js | 24 LTS | Supported since 1.329 |
| Java | 25 LTS | Fully supported since 1.321 |
| nginx | 1.30.4 | Supported since 1.345. **1.30.5 needs OneAgent 1.349 or newer**, so check the table before you change the image tag |
| Redis | 7.4 | Calls from Java (Lettuce/Jedis) appear in the PurePath |
| PostgreSQL | 17 | Queries from Java (JDBC) appear automatically |

Things to know:

- **nginx support table** ([docs](https://docs.dynatrace.com/docs/shortlink/nginx-binaries)) is kept per patch version. Check it again before you change the `nginx` image tag.
- **Java virtual threads** are not used on purpose. The OneAgent documentation mentions a possible deadlock risk with virtual threads under heavy load.
- **Alpine (musl)** is avoided because some OneAgent modes have limits on it.
- **nginx → frontend link in the Map**: the trace is linked (same trace ID, the frontend span has the nginx span as parent), but OneAgent does not record an outgoing (`client`) call from nginx, so the Map has no `load-balancer → frontend` line. This happens with a variable `proxy_pass` and also with a static one (checked on 2026-10-22). The NGINX documentation says outgoing requests from the `proxy` module are covered, so this looks like a gap between the documentation and the behavior. See [nginx proxy mode](#nginx-proxy-mode-static-or-dynamic) and the [Apache experiment](#experiment-apache-httpd-as-the-load-balancer).
- **HTTP calls in the frontend** use `node:http`, not the built-in `fetch`. The OneAgent documentation does not clearly list built-in `fetch`, but it does list the standard `http` module.
- **RUM auto-injection**: Express sends the HTML as a string and nginx uses `gzip off`, so OneAgent can add the RUM JavaScript. Do not add caching or compression that changes the HTML. After deploy, check that RUM sessions appear. If not, look at the `X-OneAgent-JS-Injection` header.
- **Redis client**: the backend uses Lettuce (default in Spring Data Redis). If Redis spans are missing in the trace, switch to Jedis (add the `jedis` dependency and set `spring.data.redis.client-type=jedis`).
- **Persistent data**: Redis and Postgres use PVCs. The backend also fills in the demo data again if it is empty.

### nginx proxy mode (static or dynamic)

Set with `FRONTEND_PROXY_MODE` in `k8s/load-balancer.yaml`.

| | `static` (default) | `dynamic` |
|---|---|---|
| `proxy_pass` | `http://frontend.<namespace>.svc.cluster.local:3000` | `$frontend` (a variable) |
| DNS lookup | Once, when nginx starts | On every request (cached 10 s) |
| `nginx → frontend` line in the Dynatrace Map | **Not shown** (tested on 2026-10-22, same as dynamic) | Not shown |
| Frontend pod is down | Fast `502`, then nginx answers `503` | Same |
| Frontend pod comes back | Works at once, no restart | Same |
| nginx starts before the `frontend` Service exists | nginx **fails to start** (`host not found in upstream`). Kubernetes restarts the container, and it works once the Service exists. | nginx starts normally |
| Service is deleted and created again (new ClusterIP) | nginx keeps the **old IP** until it restarts: `kubectl -n miftah-bank rollout restart deployment/load-balancer` | Picks up the new IP within about 10 s |

Normal use is not affected: scaling the frontend to 0 or killing its pod does not change the Service IP.

**How to check:** open the app, click around, then look at the `load-balancer` service in the Dynatrace Map. Or use this DQL. For nginx it returns **no rows**, because nginx only creates `server` spans:

```
fetch spans, from: now()-15m
| filter k8s.namespace.name == "miftah-bank" and dt.service.name == "load-balancer" and span.kind == "client"
| limit 5
```

To go back to the old behavior, set `FRONTEND_PROXY_MODE` to `dynamic` and run `kubectl apply -k k8s/`.

### Experiment: Apache httpd as the load balancer

Goal: find a load balancer for which OneAgent records the outgoing call, so the Map shows `load-balancer → frontend`.
The Dynatrace documentation does not say whether Apache creates `client` spans, so this is a test, not a promise.

| | nginx (default) | Apache (experiment) |
|---|---|---|
| Image | `miftah-bank-load-balancer:1.0.2` | `miftah-bank-load-balancer-apache:1.0.0` (`httpd:2.4.69`, Debian) |
| Health check path | `/nginx-health` | `/apache-health` |
| Deploy | `kubectl apply -k k8s-dockerhub` | `kubectl apply -k k8s-apache` |
| Behavior when something is down | 503 HTML, or JSON for `/api` | Same |

Steps (the Service, the Ingress and Locust do not change):

```bash
# 1. Build and push the image (see section 4.2, option C), then:
kubectl apply -k k8s-apache
kubectl -n miftah-bank rollout status deployment/load-balancer

# 2. Send some traffic (Locust is enough), wait 2-3 minutes, then check the Map.

# 3. Go back to nginx:
kubectl apply -k k8s-dockerhub
```

The Apache image is built with `httpd -t`, so a bad configuration fails the build. Apache resolves the frontend name with the normal system resolver, so the short name `frontend` works, and Apache still starts if the name cannot be resolved yet (requests get 502/503 until it can).

---

## 9. API

Everything goes through the frontend (`/api/*`). The token is stored in an `httpOnly` cookie.

| Method | Path | Description |
|---|---|---|
| POST | `/api/login` | Body `{username, password}`. Checked against Redis. |
| POST | `/api/logout` | Clears the session cookie. |
| GET | `/api/balance` | Check balance (PostgreSQL). |
| POST | `/api/transfer` | Body `{amount}`: a whole number from 1 to 1.000.000.000. The balance goes up and the history is saved. |
| GET | `/api/transactions` | The latest 20 transactions. |
| GET | `/healthz` | Frontend health check (does not check the backend). |

The backend (port 8081) also has `GET /healthz` and `GET /api/health/dependencies` (Redis and Postgres status, for manual checks).

Error responses are JSON, for example:

```json
{ "status": 503, "code": "DB_UNAVAILABLE", "message": "Database error: cannot reach PostgreSQL", "timestamp": "2026-10-19T13:45:00Z" }
```

---

## 10. Run locally (without Kubernetes)

```bash
# Dependencies
docker run -d --name mb-redis -p 6379:6379 redis:7.4.11-bookworm
docker run -d --name mb-pg -p 5432:5432 -e POSTGRES_DB=miftahbank -e POSTGRES_USER=miftah \
  -e POSTGRES_PASSWORD=miftah123 postgres:17.11

# Backend (needs JDK 25 and Maven). This also runs the unit tests.
cd backend && mvn package && java -jar target/miftah-bank-backend.jar      # :8081

# Frontend (needs Node.js 24)
cd frontend && npm ci && BACKEND_URL=http://localhost:8081 node server.js   # :3000
```

Open `http://localhost:3000`. nginx is not needed for local tests.

---

## 11. Troubleshooting

| Problem | What to check |
|---|---|
| Pod status `ErrImagePull` or `ImagePullBackOff` | The image is not on the node. Run `./scripts/build-images.sh --import-k3s`, then `kubectl -n miftah-bank rollout restart deployment`. Check with `sudo k3s ctr -n k8s.io images ls \| grep miftah-bank`. |
| `http://<node-IP>/` does not open | `kubectl -n miftah-bank get ingress,pods`. Check that Traefik runs: `kubectl -n kube-system get pods,svc`. |
| No data in Dynatrace | Check the DynaKube and that the namespace is monitored. Restart the deployments after the DynaKube is ready. |
| Locust shows 100% failures | Check the host in the web UI. Open the same URL in your browser. Check that the app is not in a failure scenario (`./scripts/simulate-failure.sh status`). |
| Load-balancer pod restarts, log says `host not found in upstream` | nginx started before the `frontend` Service existed (static mode). It recovers by itself after the restart. Check `kubectl -n miftah-bank get svc frontend`. |
| Backend log: "Redis is not ready yet, will retry the seed" (or the same for PostgreSQL) | Redis or Postgres is not ready yet. The backend retries every 5 seconds and stops when it works. |

---

## 12. Roadmap

- [ ] **Native Android app (Kotlin)** with the Dynatrace Mobile agent. Unlike web, the mobile agent is added to the app code, and the `x-dynatrace` header must be passed on so mobile sessions connect to backend traces.
- [ ] Synthetic monitor (browser clickpath: login, check balance, transfer).
- [ ] Dynatrace configuration as code (dashboards, alerts, SLOs) with Monaco.
