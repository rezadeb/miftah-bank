// Miftah Bank - Frontend (Node.js 24 + Express 5)
//
// What this server does:
//   1. Serves the web pages (Login and Home).
//   2. Acts as a BFF (backend for frontend): forwards /api/* calls to the Java backend over HTTP.
//   3. If the backend is down, it answers HTTP 503 with a clear message.
//
// The login token is kept in an httpOnly cookie (mb_token), not in browser JavaScript.

import express from 'express';
import http from 'node:http';
import https from 'node:https';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const PORT = Number(process.env.PORT || 3000);
const BACKEND_URL = (process.env.BACKEND_URL || 'http://localhost:8081').replace(/\/$/, '');
const BACKEND_TIMEOUT_MS = Number(process.env.BACKEND_TIMEOUT_MS || 5000);
const SESSION_MINUTES = Number(process.env.SESSION_MINUTES || 60);
const COOKIE_NAME = 'mb_token';

// The HTML files are read once at startup and sent as strings (not streamed from disk).
// This lets OneAgent inject its RUM JavaScript into the HTML response.
const readView = (name) => readFileSync(path.join(__dirname, 'views', name), 'utf8');
const LOGIN_HTML = readView('login.html');
const HOME_HTML = readView('home.html');

// ---------------------------------------------------------------- helpers

class BackendUnavailableError extends Error {
  constructor(cause) {
    super('Backend is not reachable');
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
        const index = part.indexOf('=');
        return [part.slice(0, index), decodeURIComponent(part.slice(index + 1))];
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

/**
 * Calls the backend. A connection error or a timeout becomes a BackendUnavailableError.
 *
 * This uses the classic node:http module on purpose. Dynatrace documents automatic tracing for
 * "Undici HTTP client requests", but it does not say that the built-in global fetch() is traced.
 * node:http is the safe choice, so the trace Node.js -> Java is never broken.
 */
function callBackend(pathname, { method = 'GET', body, token } = {}) {
  return new Promise((resolve, reject) => {
    const url = new URL(BACKEND_URL + pathname);
    const client = url.protocol === 'https:' ? https : http;

    const payload = body !== undefined ? JSON.stringify(body) : undefined;
    const headers = { Accept: 'application/json' };
    if (token) headers.Authorization = `Bearer ${token}`;
    if (payload !== undefined) {
      headers['Content-Type'] = 'application/json';
      headers['Content-Length'] = Buffer.byteLength(payload);
    }

    const request = client.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname + url.search,
        method,
        headers,
        timeout: BACKEND_TIMEOUT_MS,
      },
      (response) => {
        const chunks = [];
        response.on('data', (chunk) => chunks.push(chunk));
        response.on('error', (err) => reject(new BackendUnavailableError(err)));
        response.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          let data = null;
          try {
            data = text ? JSON.parse(text) : null;
          } catch {
            data = null;
          }
          resolve({ status: response.statusCode, data });
        });
      },
    );

    request.on('timeout', () => request.destroy(Object.assign(new Error('Backend timeout'), { code: 'ETIMEDOUT' })));
    request.on('error', (err) => reject(new BackendUnavailableError(err)));
    if (payload !== undefined) request.write(payload);
    request.end();
  });
}

/** Wraps a handler. If the backend is down, answer 503 with a message for that feature. */
const withBackend = (failureMessage, handler) => async (req, res) => {
  try {
    await handler(req, res);
  } catch (err) {
    if (err instanceof BackendUnavailableError) {
      const reason = err.cause?.code || err.cause?.message || 'unknown';
      console.error(`[frontend] ${req.method} ${req.path} -> backend unavailable (${reason})`);
      res.status(503).json(errorBody(503, 'BACKEND_UNAVAILABLE', failureMessage));
      return;
    }
    console.error(`[frontend] ${req.method} ${req.path} -> unexpected error`, err);
    res.status(500).json(errorBody(500, 'FRONTEND_ERROR', 'Something went wrong in the frontend'));
  }
};

/** Pass the backend answer through as it is (including 4xx/5xx errors and their codes). */
function relay(res, { status, data }) {
  res.status(status).json(data ?? errorBody(status, 'EMPTY_RESPONSE', 'The backend sent an empty response'));
}

function requireToken(req, res) {
  const token = getToken(req);
  if (!token) {
    res.status(401).json(errorBody(401, 'UNAUTHORIZED', 'Please log in first'));
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

// Simple access log to stdout (OneAgent log monitoring can pick it up).
app.use((req, res, next) => {
  if (req.path === '/healthz' || req.path.startsWith('/assets/')) return next();
  const started = process.hrtime.bigint();
  res.on('finish', () => {
    const ms = Number(process.hrtime.bigint() - started) / 1e6;
    console.log(`[frontend] ${req.method} ${req.originalUrl} ${res.statusCode} ${ms.toFixed(1)}ms`);
  });
  next();
});

// Health check for Kubernetes. It does NOT check the backend, on purpose.
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
  withBackend('Login failed: backend service is unavailable', async (req, res) => {
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
  withBackend('Check balance failed: backend service is unavailable', async (req, res) => {
    const token = requireToken(req, res);
    if (!token) return;
    relay(res, await callBackend('/api/account/balance', { token }));
  }),
);

app.post(
  '/api/transfer',
  withBackend('Transaction failed: backend service is unavailable', async (req, res) => {
    const token = requireToken(req, res);
    if (!token) return;
    const amount = Number(req.body?.amount);
    if (!Number.isInteger(amount) || amount < 1) {
      return res.status(400).json(errorBody(400, 'INVALID_AMOUNT', 'Amount must be a whole number greater than 0'));
    }
    relay(res, await callBackend('/api/transfer', { method: 'POST', body: { amount }, token }));
  }),
);

app.get(
  '/api/transactions',
  withBackend('Could not load history: backend service is unavailable', async (req, res) => {
    const token = requireToken(req, res);
    if (!token) return;
    relay(res, await callBackend('/api/transactions?limit=20', { token }));
  }),
);

app.use('/api', (_req, res) => res.status(404).json(errorBody(404, 'NOT_FOUND', 'Endpoint not found')));

const server = app.listen(PORT, () => {
  console.log(`[frontend] Miftah Bank frontend is running on port ${PORT}, backend: ${BACKEND_URL}`);
});

for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => {
    console.log(`[frontend] received ${signal}, shutting down...`);
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 5000).unref();
  });
}
