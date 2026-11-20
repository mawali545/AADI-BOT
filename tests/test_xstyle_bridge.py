import importlib.util
from pathlib import Path
import unittest

MODULE_PATH = Path(__file__).resolve().parents[1] / "xstyle_bridge.py"
SPEC = importlib.util.spec_from_file_location("xstyle_bridge", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PayloadTests(unittest.TestCase):
    def test_socketio_prefixed_price_packet(self):
        parsed = MODULE.inspect_payload('42["quotes/stream",{"price":1.2345}]')
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed["price"], 1.2345)

    def test_unrelated_packet_is_ignored(self):
        parsed = MODULE.inspect_payload('42["profile/update",{"name":"test"}]')
        self.assertIsNone(parsed)

    def test_unknown_text_does_not_invent_price(self):
        parsed = MODULE.inspect_payload("42[unknown packet]")
        self.assertIsNone(parsed)


if __name__ == "__main__":
    unittest.main()
