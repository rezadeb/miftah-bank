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

// --- User info from sessionStorage (saved at login) ----------------------
const user = JSON.parse(sessionStorage.getItem('mb_user') || 'null');
if (user) {
  document.getElementById('user-name').textContent = user.fullName;
  document.getElementById('user-acct').textContent = `Account ${user.accountNo}`;
}

/** Session expired or token rejected: clear the cookie and go back to the login page. */
async function goToLogin() {
  await fetch('/api/logout', { method: 'POST' }).catch(() => {});
  sessionStorage.removeItem('mb_user');
  window.location.href = '/';
}

/** Runs an action. A 401 sends the user to login. Other errors are shown with onError. */
async function guarded(action, prefix, onError = (message) => showAlert(alertBox, 'err', message)) {
  try {
    return await action();
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      await goToLogin();
      return undefined;
    }
    onError(describeError(prefix, err));
    return undefined;
  }
}

function showBalance(amount, isoTime) {
  balanceValue.textContent = formatRupiah(amount);
  balanceAsOf.textContent = `As of ${formatDate(isoTime)} ${formatTime(isoTime)} WIB`;
  balanceBox.hidden = false;
}

// --- Check Balance ---------------------------------------------------------
btnBalance.addEventListener('click', async () => {
  hideAlert(alertBox);
  btnBalance.disabled = true;
  const data = await guarded(() => api('/api/balance'), 'Check balance failed');
  btnBalance.disabled = false;
  if (data) showBalance(data.balance, data.asOf);
});

// --- Transfer Money --------------------------------------------------------
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
    showAlert(alertBox, 'err', 'Transaction failed: amount must be a whole number greater than 0');
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
    `Transfer of ${formatRupiah(data.amount)} successful. Your new balance is ${formatRupiah(data.balance)} (transaction #${data.transactionId}).`,
  );
  amountInput.value = '';
  showBalance(data.balance, data.createdAt);
  await loadHistory();
});

// --- Transaction History ---------------------------------------------------
async function loadHistory() {
  hideAlert(historyAlert);
  const rows = await guarded(
    () => api('/api/transactions'),
    'Could not load history',
    (message) => showAlert(historyAlert, 'err', message),
  );
  if (!rows) return;

  if (rows.length === 0) {
    historyBody.innerHTML = '<tr><td colspan="5" class="empty">No transactions yet. Try "Transfer Money" above.</td></tr>';
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
      for (const [text, className] of cells) {
        const td = document.createElement('td');
        td.textContent = text;
        if (className) td.className = className;
        tr.appendChild(td);
      }
      return tr;
    }),
  );
}

document.getElementById('btn-refresh').addEventListener('click', loadHistory);
document.getElementById('btn-logout').addEventListener('click', goToLogin);

loadHistory();
