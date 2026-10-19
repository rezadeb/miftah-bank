import { api, describeError, hideAlert, showAlert } from '/assets/js/common.js';

const form = document.getElementById('login-form');
const button = document.getElementById('btn-login');
const alertBox = document.getElementById('alert');

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  hideAlert(alertBox);
  button.disabled = true;
  button.textContent = 'Please wait...';

  try {
    const user = await api('/api/login', {
      method: 'POST',
      body: {
        username: document.getElementById('username').value,
        password: document.getElementById('password').value,
      },
    });
    sessionStorage.setItem('mb_user', JSON.stringify(user));
    window.location.href = '/home';
  } catch (err) {
    showAlert(alertBox, 'err', describeError('Login failed', err));
    button.disabled = false;
    button.textContent = 'Log in';
  }
});
