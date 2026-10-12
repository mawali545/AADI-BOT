import asyncio
import unittest

from config import ASSET, TIMEFRAME
from scanner import Scanner


class ScannerTests(unittest.TestCase):
    def test_single_tick_payload_updates_price_and_candle(self):
        scanner = Scanner()
        ts = 1_700_000_000
        asyncio.run(scanner.handle_event("quotes/stream", [ASSET, ts, 1.23456]))
        self.assertEqual(scanner.latest["price"], 1.23456)
        self.assertIsNotNone(scanner.current_candle)
        self.assertEqual(scanner.current_candle["close"], 1.23456)

    def test_batched_ticks_update_same_candle(self):
        scanner = Scanner()
        ts = 1_700_000_000
        asyncio.run(scanner.handle_event("quotes/stream", [
            [ASSET, ts, 1.2],
            [ASSET, ts + 1, 1.4],
        ]))
        self.assertEqual(scanner.latest["price"], 1.4)
        self.assertEqual(scanner.current_candle["high"], 1.4)
        self.assertEqual(scanner.current_candle["low"], 1.2)

    def test_other_asset_is_ignored(self):
        scanner = Scanner()
        asyncio.run(scanner.handle_event("quotes/stream", ["GBPUSD_otc", 1_700_000_000, 9.9]))
        self.assertIsNone(scanner.latest["price"])


if __name__ == "__main__":
    unittest.main()
