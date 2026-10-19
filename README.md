# Miftah Bank

Aplikasi **dummy banking** untuk **demo Dynatrace OneAgent** di Kubernetes (K3s). Aplikasinya sengaja sederhana, tetapi punya rantai layanan lengkap dan skenario kegagalan per komponen, supaya mudah dipakai untuk menunjukkan Service Flow, distributed trace (PurePath), log, Kubernetes, RUM, dan Davis Problem.

> **Hanya untuk demo lab.** Password disimpan sebagai SHA-256 di Redis, secret ada di git, dan token login bukan JWT standar. Jangan dipakai untuk production.

---

## 1. Arsitektur

```
 Browser (Web)                                   [di luar cluster]
      |  HTTP :80
 Ingress (Traefik, bawaan K3s)
 ======|=========================== namespace: miftah-bank ===========
      v  HTTP :80 -> :8080
 load-balancer   nginx 1.30.4
      |  HTTP :3000
      v
 frontend        Node.js 24 LTS (Express)   halaman web + BFF /api/*
      |  HTTP :8081
      v
 backend         Java 25 LTS (Spring Boot 4.1)
      |  RESP :6379                 |  JDBC :5432
      v                             v
 redis 7.4  (validasi login)     postgres 17  (saldo + riwayat transaksi)
 ===================================================================
```

| Komponen | Teknologi | Port | Peran |
|---|---|---|---|
| `load-balancer` | nginx 1.30.4 (Debian) | 8080 (Service: 80) | Reverse proxy ke frontend. Membalas 503 sendiri kalau frontend mati. |
| `frontend` | Node.js 24 LTS + Express 5 | 3000 | Halaman Login/Home, meneruskan `/api/*` ke backend (BFF). |
| `backend` | Java 25 LTS + Spring Boot 4.1.1 | 8081 | Logika bisnis: login ke Redis, saldo/transfer ke PostgreSQL. |
| `redis` | Redis 7.4 | 6379 | Data user untuk **login**. |
| `postgres` | PostgreSQL 17 | 5432 | Saldo dan riwayat **transaksi**. |

Semua berjalan di satu namespace: **`miftah-bank`** (5 deployment).

### Keputusan desain

- **Failure domain terpisah.** Token login bersifat stateless (HMAC), jadi Redis hanya dipakai saat *login*. Kalau Redis mati, user yang sudah login masih bisa cek saldo/transfer. Kalau Postgres mati, login tetap jalan.
- **Probe tidak mengecek dependency.** Kalau database mati, pod backend tetap *Ready* dan membalas HTTP 503 per request (tidak di-restart), supaya skenario demo konsisten.
- **Error cepat dan jelas.** Timeout koneksi Redis dan Postgres sekitar 2 detik, dengan kode error yang eksplisit (`REDIS_UNAVAILABLE`, `DB_UNAVAILABLE`, `BACKEND_UNAVAILABLE`, `FRONTEND_UNAVAILABLE`).
- **Image Debian/Ubuntu (glibc), bukan Alpine**, agar injeksi OneAgent berjalan mulus.

---

## 2. Fitur

- **Login** (username + password) divalidasi ke **Redis**.
- **Cek Saldo**: membaca saldo dari **PostgreSQL**, ditampilkan dalam Rupiah.
- **Transfer Uang**: isi nominal, saldo **selalu bertambah** sebesar nominal tersebut, lalu tercatat di tabel riwayat (**tanggal, jam, jumlah**, saldo setelahnya).
- **Riwayat Transaksi**: 20 transaksi terbaru, waktu WIB.

**Akun demo** (dibuat otomatis oleh backend, idempotent):

| Username | Password | Saldo awal |
|---|---|---|
| `demo` | `demo123` | Rp 1.000.000 |
| `miftah` | `miftah123` | Rp 5.000.000 |
| `alice` | `alice123` | Rp 2.500.000 |

---

## 3. Struktur repo

```
backend/        Spring Boot (Java 25) + Dockerfile
frontend/       Node.js 24 (Express) + views/ + public/ + Dockerfile
loadbalancer/   nginx (template config, halaman error 503) + Dockerfile
k8s/            Manifest Kubernetes (kustomize), namespace miftah-bank
dynatrace/      Contoh DynaKube (cloud native full stack)
scripts/        build-images.sh, deploy.sh, simulate-failure.sh, load-generator.sh
```

---

## 4. Deploy ke K3s (miftah-ubuntu)

Prasyarat: K3s berjalan (Traefik aktif), `kubectl`, Docker di node tempat image dibuild, tenant Dynatrace SaaS.

### 4.1 Pasang Dynatrace Operator + DynaKube

Ikuti dokumentasi resmi: [Get started with full observability (cloud native full stack)](https://docs.dynatrace.com/docs/ingest-from/setup-on-k8s/installation/cloud-native-fullstack). Ringkasnya (dicek 2026-10-19):

```bash
helm install dynatrace-operator oci://public.ecr.aws/dynatrace/dynatrace-operator \
  --create-namespace --namespace dynatrace --atomic    # Helm 4: ganti --atomic dengan --rollback-on-failure

kubectl -n dynatrace create secret generic dynakube \
  --from-literal="apiToken=<OPERATOR_TOKEN>" \
  --from-literal="dataIngestToken=<DATA_INGEST_TOKEN>"

kubectl apply -f dynatrace/dynakube.yaml   # sesuaikan apiUrl dan apiVersion dengan versi Operator
```

Scope token ada di halaman *Tokens and permissions required* pada dokumentasi di atas. Secara default OneAgent disuntik ke semua namespace, termasuk `miftah-bank`.

### 4.2 Build image dan deploy

```bash
git clone https://github.com/rezadeb/miftah-bank.git && cd miftah-bank

./scripts/build-images.sh --import-k3s   # build 3 image + import ke containerd K3s (tanpa registry)
./scripts/deploy.sh                      # kubectl apply -k k8s/ + tunggu semua rollout
```

Buka `http://<IP-node-K3s>/` lalu login dengan `demo` / `demo123`.

> Urutan paling aman: pasang DynaKube **sebelum** `deploy.sh`, supaya pod langsung disuntik OneAgent. Kalau aplikasi sudah jalan lebih dulu, lakukan `kubectl -n miftah-bank rollout restart deployment`.

### 4.3 Bangkitkan trafik

```bash
BASE_URL=http://<IP-node-K3s> ./scripts/load-generator.sh
```

---

## 5. Skenario demo kegagalan

```bash
./scripts/simulate-failure.sh down <komponen>   # scale ke 0 replika
./scripts/simulate-failure.sh up   <komponen>   # hidupkan lagi (1 replika)
./scripts/simulate-failure.sh status
# komponen: load-balancer | frontend | backend | redis | postgres | all
```

| Pod mati | Yang dilihat user | Kode / sumber error |
|---|---|---|
| `load-balancer` | HTTP **503 Service Unavailable** | Dari Ingress (Traefik), karena Service tidak punya endpoint |
| `frontend` | HTTP **503**, halaman "Service Unavailable" (HTML) atau JSON untuk `/api` | `FRONTEND_UNAVAILABLE`, dijawab **nginx** |
| `backend` | HTTP **503** "Login failed" / "Transaction failed" | `BACKEND_UNAVAILABLE`, dijawab frontend |
| `redis` | Login gagal, HTTP **503** | `REDIS_UNAVAILABLE`, dari backend |
| `postgres` | Cek saldo / transfer / riwayat gagal, HTTP **503** "Database error" | `DB_UNAVAILABLE`, dari backend |

Catatan: untuk `load-balancer`, error datang dari lapisan **di depan** nginx, karena tidak ada nginx yang berjalan untuk menjawab.

---

## 6. Yang bisa ditunjukkan di Dynatrace

- **Smartscape / Service Flow**: `nginx → Node.js → Java → Redis / PostgreSQL`.
- **Distributed trace** satu klik "Transfer Uang": frontend (Node) → backend (Java) → query PostgreSQL.
- **Failure detection** otomatis saat pod dimatikan, termasuk HTTP 5xx dan exception `REDIS_UNAVAILABLE` / `DB_UNAVAILABLE`, lalu **Davis Problem**.
- **Kubernetes**: perubahan replika, pod restart, event.
- **Log**: log aplikasi dari stdout (frontend, backend, nginx access log dengan `rt=` dan `upstream=`).
- **RUM** (web): sesi user dan user action (Login, Cek Saldo, Transfer Uang).

Contoh DQL untuk log error (selalu batasi rentang waktu dan `limit` agar hemat biaya query):

```
fetch logs, from: now()-30m
| filter k8s.namespace.name == "miftah-bank"
| filter loglevel == "ERROR"
| limit 50
```

---

## 7. Catatan kompatibilitas OneAgent

Dicek ke dokumentasi resmi Dynatrace pada **2026-10-19** (target OneAgent **1.347**):

| Teknologi | Versi dipakai | Dukungan OneAgent |
|---|---|---|
| Node.js | 24 LTS | Didukung sejak 1.329 |
| Java | 25 LTS | Didukung penuh sejak 1.321 (Java 21 sejak 1.275) |
| nginx | 1.30.4 | Didukung sejak 1.345. **1.30.5 butuh OneAgent ≥ 1.349**, jangan naikkan tag image tanpa cek tabel dulu |
| Redis | 7.4 | Panggilan dari Java (Lettuce/Jedis) terlihat sebagai bagian dari PurePath |
| PostgreSQL | 17 | Query dari Java via JDBC terlihat otomatis |

Hal yang perlu diperhatikan:

- **Tabel dukungan nginx** ([docs](https://docs.dynatrace.com/docs/shortlink/nginx-binaries)) dipegang per versi patch. Cek ulang sebelum mengganti tag image `nginx`.
- **Virtual threads Java** sengaja tidak diaktifkan: dokumentasi OneAgent 1.329 menyebut potensi deadlock pada Java 21-24 dengan virtual threads di beban berat.
- **Alpine (musl)** dihindari karena ada batasan dukungan pada beberapa mode OneAgent.
- **RUM auto-injection**: HTML disajikan sebagai string oleh Express dan nginx memakai `gzip off`, supaya OneAgent bisa menyisipkan JavaScript RUM. Jangan menambah cache/kompresi yang mengubah respons HTML. Setelah deploy, verifikasi sesi RUM muncul. Kalau tidak, cek header `X-OneAgent-JS-Injection`.
- **Redis client**: backend memakai Lettuce (bawaan Spring Data Redis). Kalau span Redis tidak muncul di trace, ganti ke Jedis (tambah dependency `jedis` dan set `spring.data.redis.client-type=jedis`).
- **Data persisten**: Redis dan Postgres memakai PVC. Backend juga mengisi ulang data demo secara otomatis jika kosong.

---

## 8. API

Semua lewat frontend (`/api/*`). Token disimpan di cookie `httpOnly`.

| Method | Path | Keterangan |
|---|---|---|
| POST | `/api/login` | Body `{username, password}`; validasi ke Redis |
| POST | `/api/logout` | Hapus cookie sesi |
| GET | `/api/balance` | Cek saldo (PostgreSQL) |
| POST | `/api/transfer` | Body `{amount}` (1 s/d 1.000.000.000); saldo bertambah + riwayat tercatat |
| GET | `/api/transactions` | 20 transaksi terbaru |
| GET | `/healthz` | Health check frontend (tidak mengecek backend) |

Backend (port 8081) juga punya `GET /healthz` dan `GET /api/health/dependencies` (status Redis/Postgres, untuk diagnostik manual).

---

## 9. Menjalankan lokal (tanpa Kubernetes)

```bash
# Dependency
docker run -d --name mb-redis -p 6379:6379 redis:7.4.11-bookworm
docker run -d --name mb-pg -p 5432:5432 -e POSTGRES_DB=miftahbank -e POSTGRES_USER=miftah \
  -e POSTGRES_PASSWORD=miftah123 postgres:17.11

# Backend (butuh JDK 25 dan Maven)
cd backend && mvn package && java -jar target/miftah-bank-backend.jar      # :8081

# Frontend (butuh Node.js 24)
cd frontend && npm ci && BACKEND_URL=http://localhost:8081 node server.js   # :3000
```

Buka `http://localhost:3000`. Nginx tidak wajib untuk uji lokal.

---

## 10. Roadmap

- [ ] **Aplikasi mobile native Android (Kotlin)** dengan Dynatrace Mobile agent. Beda dengan web, agent mobile dipasang di kode aplikasi dan header `x-dynatrace` harus diteruskan agar sesi mobile tersambung ke trace backend.
- [ ] Synthetic monitor (browser clickpath: login, cek saldo, transfer).
- [ ] Konfigurasi Dynatrace as code (dashboard, alert, SLO) dengan Monaco.
