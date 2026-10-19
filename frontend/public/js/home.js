import {
  ApiError, api, describeError, formatDate, formatRupiah, formatTime, hideAlert, showAlert,
} from '/assets/js/common.js';

const alertBox = document.getElementById('alert');
const balanceBox = document.getElementById('balance-box');
const balanceValue = document.getElementById('balance-value');
const balanceAsOf = document.getElementById('balance-asof');
const btnBalance = document.getElementById('btn-balance');
const btnTransfer = document.getElementById('btn-transfer');
const amountInput = document.getElementById('amount');
const historyBody = document.getElementById('history-body');
const historyAlert = document.getElementById('history-alert');

// --- profil dari sessionStorage (diisi saat login) ------------------------
const user = JSON.parse(sessionStorage.getItem('mb_user') || 'null');
if (user) {
  document.getElementById('user-name').textContent = user.fullName;
  document.getElementById('user-acct').textContent = `Rek. ${user.accountNo}`;
}

/** Sesi habis / token ditolak -> bersihkan cookie lalu kembali ke login. */
async function handleSessionExpired() {
  await fetch('/api/logout', { method: 'POST' }).catch(() => {});
  sessionStorage.removeItem('mb_user');
  window.location.href = '/';
}

async function guarded(action, prefix, onError = (message) => showAlert(alertBox, 'err', message)) {
  try {
    return await action();
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      await handleSessionExpired();
      return undefined;
    }
    onError(describeError(prefix, err));
    return undefined;
  }
}

// --- Cek Saldo ---------------------------------------------------------------
btnBalance.addEventListener('click', async () => {
  hideAlert(alertBox);
  btnBalance.disabled = true;
  const data = await guarded(() => api('/api/balance'), 'Cek saldo gagal');
  btnBalance.disabled = false;
  if (!data) return;
  balanceValue.textContent = formatRupiah(data.balance);
  balanceAsOf.textContent = `Per ${formatDate(data.asOf)} ${formatTime(data.asOf)} WIB`;
  balanceBox.hidden = false;
});

// --- Transfer Uang -----------------------------------------------------------
document.querySelectorAll('.chip').forEach((chip) => {
  chip.addEventListener('click', () => {
    amountInput.value = chip.dataset.amount;
    amountInput.focus();
  });
});

document.getElementById('transfer-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  hideAlert(alertBox);

  const amount = Number(amountInput.value);
  if (!Number.isInteger(amount) || amount < 1) {
    showAlert(alertBox, 'err', 'Transaction failed - nominal harus berupa bilangan bulat positif');
    return;
  }

  btnTransfer.disabled = true;
  const data = await guarded(
    () => api('/api/transfer', { method: 'POST', body: { amount } }),
    'Transaction failed',
  );
  btnTransfer.disabled = false;
  if (!data) return;

  showAlert(
    alertBox,
    'ok',
    `Transfer ${formatRupiah(data.amount)} berhasil. Saldo sekarang ${formatRupiah(data.balance)} (ID transaksi #${data.transactionId}).`,
  );
  amountInput.value = '';
  balanceValue.textContent = formatRupiah(data.balance);
  balanceAsOf.textContent = `Per ${formatDate(data.createdAt)} ${formatTime(data.createdAt)} WIB`;
  balanceBox.hidden = false;
  await loadHistory();
});

// --- Riwayat -------------------------------------------------------------------
async function loadHistory() {
  hideAlert(historyAlert);
  const rows = await guarded(
    () => api('/api/transactions'),
    'Riwayat gagal dimuat',
    (message) => showAlert(historyAlert, 'err', message),
  );
  if (!rows) return;

  if (rows.length === 0) {
    historyBody.innerHTML = '<tr><td colspan="5" class="empty">Belum ada transaksi. Coba Transfer Uang di atas.</td></tr>';
    return;
  }

  historyBody.replaceChildren(
    ...rows.map((row) => {
      const tr = document.createElement('tr');
      const cells = [
        [`#${row.id}`, ''],
        [formatDate(row.createdAt), ''],
        [`${formatTime(row.createdAt)} WIB`, ''],
        [`+ ${formatRupiah(row.amount)}`, 'num plus'],
        [formatRupiah(row.balanceAfter), 'num'],
      ];
      for (const [text, cls] of cells) {
        const td = document.createElement('td');
        td.textContent = text;
        if (cls) td.className = cls;
        tr.appendChild(td);
      }
      return tr;
    }),
  );
}

document.getElementById('btn-refresh').addEventListener('click', loadHistory);

document.getElementById('btn-logout').addEventListener('click', async () => {
  await handleSessionExpired();
});

loadHistory();
