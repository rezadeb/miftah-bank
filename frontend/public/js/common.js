// Helper bersama untuk halaman Login dan Home.

const rupiah = new Intl.NumberFormat('id-ID', {
  style: 'currency',
  currency: 'IDR',
  maximumFractionDigits: 0,
});

export const formatRupiah = (value) => rupiah.format(value);

export const formatDate = (iso) =>
  new Date(iso).toLocaleDateString('id-ID', {
    timeZone: 'Asia/Jakarta',
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  });

export const formatTime = (iso) =>
  new Date(iso).toLocaleTimeString('id-ID', {
    timeZone: 'Asia/Jakarta',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  });

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

/** Panggil API frontend. Respons non-2xx dilempar sebagai ApiError (termasuk 503 dari nginx/Ingress). */
export async function api(path, { method = 'GET', body } = {}) {
  let response;
  try {
    response = await fetch(path, {
      method,
      credentials: 'same-origin',
      headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', 'Tidak dapat terhubung ke server');
  }

  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null; // mis. halaman HTML/teks 503 dari Ingress atau nginx
  }

  if (!response.ok) {
    const fallback = response.status === 503 ? 'Layanan tidak tersedia (Service Unavailable)' : `HTTP ${response.status}`;
    throw new ApiError(response.status, data?.code ?? `HTTP_${response.status}`, data?.message ?? fallback);
  }
  return data;
}

export function showAlert(el, kind, text) {
  el.className = `alert show ${kind}`;
  el.textContent = text;
}

export function hideAlert(el) {
  el.className = 'alert';
  el.textContent = '';
}

/** Teks error: "Login failed: Redis tidak dapat dihubungi... (HTTP 503 REDIS_UNAVAILABLE)" */
export function describeError(prefix, err) {
  if (!(err instanceof ApiError)) return `${prefix}: terjadi kesalahan tak terduga`;
  const detail = err.message.startsWith(prefix) ? err.message : `${prefix}: ${err.message}`;
  const status = err.status ? `HTTP ${err.status} ` : '';
  return `${detail} (${status}${err.code})`;
}
