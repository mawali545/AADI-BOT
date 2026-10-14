import unittest

from quotex_ws import _json_from_bytes, _replace_binary_placeholders


class BinaryPacketTests(unittest.TestCase):
    def test_decodes_json_binary_attachment(self):
        self.assertEqual(_json_from_bytes(b'{"price":1.23}'), {"price": 1.23})

    def test_decodes_engine_io_prefixed_json(self):
        self.assertEqual(_json_from_bytes(b'\x04[1,2,3]'), [1, 2, 3])

    def test_replaces_socketio_binary_placeholder(self):
        payload = {"data": {"_placeholder": True, "num": 0}}
        result = _replace_binary_placeholders(payload, [{"candles": [1, 2]}])
        self.assertEqual(result, {"data": {"candles": [1, 2]}})

    def test_preserves_unresolved_placeholder(self):
        payload = {"_placeholder": True, "num": 2}
        self.assertEqual(_replace_binary_placeholders(payload, []), payload)

    def test_rejects_non_json_binary_without_faking_data(self):
        self.assertIsNone(_json_from_bytes(b'\x00\xff\x10\x80'))


if __name__ == "__main__":
    unittest.main()
