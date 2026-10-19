// Helpers shared by the Login and Home pages.

// Rupiah format, for example "Rp 1.000.000".
const rupiah = new Intl.NumberFormat('id-ID', {
  style: 'currency',
  currency: 'IDR',
  maximumFractionDigits: 0,
});

export const formatRupiah = (value) => rupiah.format(value);

// Date and time are always shown in Jakarta time (WIB).
export const formatDate = (iso) =>
  new Date(iso).toLocaleDateString('en-GB', {
    timeZone: 'Asia/Jakarta',
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  });

export const formatTime = (iso) =>
  new Date(iso).toLocaleTimeString('en-GB', {
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

/**
 * Calls our own server (/api/...). Any non-2xx answer is thrown as an ApiError.
 * This includes a 503 page from nginx or the Ingress, which may not be JSON.
 */
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
    throw new ApiError(0, 'NETWORK_ERROR', 'Cannot connect to the server');
  }

  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null; // For example a plain-text 503 from the Ingress.
  }

  if (!response.ok) {
    const fallbackCode = response.status === 503 ? 'SERVICE_UNAVAILABLE' : `HTTP_${response.status}`;
    const fallbackMessage = response.status === 503 ? 'Service Unavailable' : `HTTP ${response.status}`;
    throw new ApiError(response.status, data?.code ?? fallbackCode, data?.message ?? fallbackMessage);
  }
  return data;
}

export function showAlert(element, kind, text) {
  element.className = `alert show ${kind}`;
  element.textContent = text;
}

export function hideAlert(element) {
  element.className = 'alert';
  element.textContent = '';
}

/** Builds the error text, for example: "Login failed: Cannot reach Redis... (HTTP 503 REDIS_UNAVAILABLE)". */
export function describeError(prefix, err) {
  if (!(err instanceof ApiError)) return `${prefix}: unexpected error`;
  const detail = err.message.startsWith(prefix) ? err.message : `${prefix}: ${err.message}`;
  const status = err.status ? `HTTP ${err.status} ` : '';
  return `${detail} (${status}${err.code})`;
}
