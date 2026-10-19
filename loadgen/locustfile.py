"""Locust load test for Miftah Bank.

Each virtual user logs in, then repeats: check balance, transfer money, view history.
You can change the number of users and the spawn rate at any time in the Locust web UI.

Settings (environment variables, all optional):
  BANK_USERS      demo accounts to use, "user:password,user:password" (default: the 3 seed accounts)
  WAIT_MIN        minimum seconds between actions (default 1)
  WAIT_MAX        maximum seconds between actions (default 3)
  TRANSFER_MIN    smallest transfer amount in Rp (default 10000)
  TRANSFER_MAX    largest transfer amount in Rp (default 250000)
"""
import os
import random

from locust import HttpUser, between, task


def _accounts():
    raw = os.getenv("BANK_USERS", "demo:demo123,miftah:miftah123,alice:alice123")
    return [tuple(item.split(":", 1)) for item in raw.split(",") if ":" in item]


ACCOUNTS = _accounts()
TRANSFER_MIN = int(os.getenv("TRANSFER_MIN", "10000"))
TRANSFER_MAX = int(os.getenv("TRANSFER_MAX", "250000"))


class BankUser(HttpUser):
    """A customer using the web app."""

    wait_time = between(float(os.getenv("WAIT_MIN", "1")), float(os.getenv("WAIT_MAX", "3")))

    def on_start(self):
        self.username, self.password = random.choice(ACCOUNTS)
        self.logged_in = False
        self.login()

    def login(self):
        # The cookie "mb_token" is kept automatically by the Locust HTTP session.
        with self.client.post(
            "/api/login",
            json={"username": self.username, "password": self.password},
            name="POST /api/login",
            catch_response=True,
        ) as response:
            self.logged_in = response.status_code == 200
            # A 4xx/5xx response is counted as a failure automatically.

    def _need_login(self):
        """If we are not logged in (for example Redis was down), try again first."""
        if not self.logged_in:
            self.login()
        return self.logged_in

    def _call(self, method, path, **kwargs):
        with self.client.request(method, path, name=f"{method} {path}", catch_response=True, **kwargs) as response:
            if response.status_code == 401:
                self.logged_in = False  # session expired: log in again on the next action
            return response

    @task(3)
    def check_balance(self):
        if self._need_login():
            self._call("GET", "/api/balance")

    @task(2)
    def transfer(self):
        if self._need_login():
            amount = random.randrange(TRANSFER_MIN, TRANSFER_MAX + 1, 1000)
            self._call("POST", "/api/transfer", json={"amount": amount})

    @task(2)
    def history(self):
        if self._need_login():
            self._call("GET", "/api/transactions")

    @task(1)
    def open_home_page(self):
        if self._need_login():
            self._call("GET", "/home")
