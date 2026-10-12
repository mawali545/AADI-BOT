import unittest
from indicators import ema, momentum, rsi, signal
from collections import deque

class IndicatorTests(unittest.TestCase):
    def test_ema_requires_enough_values(self):
        self.assertIsNone(ema([1, 2], 3))
        self.assertAlmostEqual(ema([1, 2, 3], 3), 2.0)

    def test_momentum(self):
        self.assertEqual(momentum([1, 2, 3, 4, 5, 6]), 5.0)
        self.assertIsNone(momentum([1, 2, 3], 5))

    def test_rsi_uptrend(self):
        self.assertEqual(rsi(list(range(1, 16)), 14), 100.0)

    def test_signal_waits_for_enough_data(self):
        out = signal(deque([1.0, 2.0, 3.0]), 9, 21, 14, 3)
        self.assertEqual(out["signal"], "WAIT")
        self.assertEqual(out["score"], 0)

if __name__ == "__main__":
    unittest.main()
