"""Same users as locustfile.py, but the number of users goes up and down in waves.

Use it when you want traffic to change by itself during a demo:
  locust -f locustfile_wave.py

Settings (environment variables, all optional):
  WAVE_LOW        users at the quiet time (default 5)
  WAVE_HIGH       users at the busy time (default 40)
  WAVE_PERIOD     seconds for one full wave (default 300)
  WAVE_SPAWN_RATE users started or stopped per second (default 5)
  WAVE_STOP_AFTER stop the test after this many seconds (default 0 = run until you stop it)
"""
import math
import os

from locust import LoadTestShape

from locustfile import BankUser  # noqa: F401  (Locust finds the user class here)

LOW = int(os.getenv("WAVE_LOW", "5"))
HIGH = int(os.getenv("WAVE_HIGH", "40"))
PERIOD = int(os.getenv("WAVE_PERIOD", "300"))
SPAWN_RATE = float(os.getenv("WAVE_SPAWN_RATE", "5"))
STOP_AFTER = int(os.getenv("WAVE_STOP_AFTER", "0"))


class WaveShape(LoadTestShape):
    """User count follows a smooth sine wave between LOW and HIGH."""

    def tick(self):
        t = self.get_run_time()
        if STOP_AFTER and t >= STOP_AFTER:
            return None  # None tells Locust to stop the test
        middle = (HIGH + LOW) / 2
        swing = (HIGH - LOW) / 2
        users = round(middle - swing * math.cos(2 * math.pi * t / PERIOD))  # starts at LOW
        return users, SPAWN_RATE
