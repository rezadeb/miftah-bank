// Miftah Bank - Frontend (Node.js 24 + Express 5)
//
// Tugas:
//   1. Menyajikan halaman web (Login + Home).
//   2. Jadi BFF: meneruskan /api/* ke backend Java (HTTP).
//   3. Kalau backend mati -> balas HTTP 503 dengan pesan yang jelas.
//
// Token login disimpan di cookie httpOnly (mb_token), bukan di JavaScript browser.

import express from 'express';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const PORT = Number(process.env.PORT || 3000);
const BACKEND_URL = (process.env.BACKEND_URL || 'http://localhost:8081').replace(/\/$/, '');
const BACKEND_TIMEOUT_MS = Number(process.env.BACKEND_TIMEOUT_MS || 5000);
const SESSION_MINUTES = Number(process.env.SESSION_MINUTES || 60);
const COOKIE_NAME = 'mb_token';

// HTML dibaca sekali saat start dan dikirim sebagai string (bukan stream file),
// supaya OneAgent bisa menyisipkan JavaScript RUM ke dalam respons HTML.
const readView = (name) => readFileSync(path.join(__dirname, 'views', name), 'utf8');
const LOGIN_HTML = readView('login.html');
const HOME_HTML = readView('home.html');

// ---------------------------------------------------------------- helpers

class BackendUnavailableError extends Error {
  constructor(cause) {
    super('Backend tidak dapat dihubungi');
    this.name = 'BackendUnavailableError';
    this.cause = cause;
  }
}

function parseCookies(header = '') {
  return Object.fromEntries(
    header
      .split(';')
      .map((part) => part.trim())
      .filter(Boolean)
      .map((part) => {
        const idx = part.indexOf('=');
        return [part.slice(0, idx), decodeURIComponent(part.slice(idx + 1))];
      }),
  );
}

const getToken = (req) => parseCookies(req.headers.cookie)[COOKIE_NAME];

function setSessionCookie(res, token) {
  res.append(
    'Set-Cookie',
    `${COOKIE_NAME}=${encodeURIComponent(token)}; HttpOnly; Path=/; SameSite=Lax; Max-Age=${SESSION_MINUTES * 60}`,
  );
}

function clearSessionCookie(res) {
  res.append('Set-Cookie', `${COOKIE_NAME}=; HttpOnly; Path=/; SameSite=Lax; Max-Age=0`);
}

function errorBody(status, code, message) {
  return { status, code, message, timestamp: new Date().toISOString() };
}

/** Panggil backend. Koneksi gagal / timeout -> BackendUnavailableError. */
async function callBackend(pathname, { method = 'GET', body, token } = {}) {
  const headers = { Accept: 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  let response;
  try {
    response = await fetch(BACKEND_URL + pathname, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(BACKEND_TIMEOUT_MS),
    });
  } catch (err) {
    throw new BackendUnavailableError(err);
  }

  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  return { status: response.status, data };
}

/** Bungkus handler: backend mati -> 503 dengan pesan sesuai fitur. */
const withBackend = (failureMessage, handler) => async (req, res) => {
  try {
    await handler(req, res);
  } catch (err) {
    if (err instanceof BackendUnavailableError) {
      const reason = err.cause?.cause?.code || err.cause?.code || err.cause?.name || 'unknown';
      console.error(`[frontend] ${req.method} ${req.path} -> backend tidak tersedia (${reason})`);
      res.status(503).json(errorBody(503, 'BACKEND_UNAVAILABLE', failureMessage));
      return;
    }
    console.error(`[frontend] ${req.method} ${req.path} -> error tak terduga`, err);
    res.status(500).json(errorBody(500, 'FRONTEND_ERROR', 'Terjadi kesalahan pada frontend'));
  }
};

/** Teruskan respons backend apa adanya (termasuk error 4xx/5xx beserta kodenya). */
function relay(res, { status, data }) {
  res.status(status).json(data ?? errorBody(status, 'EMPTY_RESPONSE', 'Respons backend kosong'));
}

function requireToken(req, res) {
  const token = getToken(req);
  if (!token) {
    res.status(401).json(errorBody(401, 'UNAUTHORIZED', 'Silakan login terlebih dahulu'));
    return null;
  }
  return token;
}

// ---------------------------------------------------------------- app

const app = express();
app.disable('x-powered-by');
app.set('etag', false);
app.set('trust proxy', true);
app.use(express.json({ limit: '10kb' }));

// Access log sederhana ke stdout (tertangkap oleh Log Monitoring OneAgent)
app.use((req, res, next) => {
  if (req.path === '/healthz' || req.path.startsWith('/assets/')) return next();
  const started = process.hrtime.bigint();
  res.on('finish', () => {
    const ms = Number(process.hrtime.bigint() - started) / 1e6;
    console.log(`[frontend] ${req.method} ${req.originalUrl} ${res.statusCode} ${ms.toFixed(1)}ms`);
  });
  next();
});

// Health check untuk Kubernetes: sengaja TIDAK mengecek backend.
app.get('/healthz', (_req, res) => res.json({ status: 'UP' }));

app.use('/assets', express.static(path.join(__dirname, 'public'), { etag: false, maxAge: '1h' }));

const sendHtml = (res, html) =>
  res.status(200).set('Cache-Control', 'no-store').type('html').send(html);

app.get('/', (_req, res) => sendHtml(res, LOGIN_HTML));

app.get('/home', (req, res) => {
  if (!getToken(req)) return res.redirect(302, '/');
  return sendHtml(res, HOME_HTML);
});

// --- API (BFF) -------------------------------------------------------------

app.post(
  '/api/login',
  withBackend('Login failed: layanan backend tidak tersedia', async (req, res) => {
    const { username, password } = req.body ?? {};
    const result = await callBackend('/api/auth/login', { method: 'POST', body: { username, password } });
    if (result.status === 200 && result.data?.token) {
      setSessionCookie(res, result.data.token);
      const { fullName, accountNo } = result.data;
      return res.json({ username: result.data.username, fullName, accountNo });
    }
    return relay(res, result);
  }),
);

app.post('/api/logout', (_req, res) => {
  clearSessionCookie(res);
  res.json({ ok: true });
});

app.get(
  '/api/balance',
  withBackend('Cek saldo gagal: layanan backend tidak tersedia', async (req, res) => {
    const token = requireToken(req, res);
    if (!token) return;
    relay(res, await callBackend('/api/account/balance', { token }));
  }),
);

app.post(
  '/api/transfer',
  withBackend('Transaction failed: layanan backend tidak tersedia', async (req, res) => {
    const token = requireToken(req, res);
    if (!token) return;
    const amount = Number(req.body?.amount);
    if (!Number.isInteger(amount) || amount < 1) {
      return res.status(400).json(errorBody(400, 'INVALID_AMOUNT', 'Nominal harus berupa bilangan bulat positif'));
    }
    relay(res, await callBackend('/api/transfer', { method: 'POST', body: { amount }, token }));
  }),
);

app.get(
  '/api/transactions',
  withBackend('Riwayat gagal dimuat: layanan backend tidak tersedia', async (req, res) => {
    const token = requireToken(req, res);
    if (!token) return;
    relay(res, await callBackend('/api/transactions?limit=20', { token }));
  }),
);

app.use('/api', (_req, res) => res.status(404).json(errorBody(404, 'NOT_FOUND', 'Endpoint tidak ditemukan')));

const server = app.listen(PORT, () => {
  console.log(`[frontend] Miftah Bank frontend berjalan di port ${PORT}, backend: ${BACKEND_URL}`);
});

for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => {
    console.log(`[frontend] menerima ${signal}, shutdown...`);
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 5000).unref();
  });
}
