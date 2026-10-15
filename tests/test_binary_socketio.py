import asyncio
import json
import unittest

from quotex_ws import QuotexSocket


class BinarySocketIOTests(unittest.TestCase):
    def test_binary_event_waits_for_all_attachments_and_replaces_placeholder(self):
        received = []

        async def on_event(event, payload):
            received.append((event, payload))

        socket = QuotexSocket(on_event)
        header = '452-["history/list/v2",{"blob":{"_placeholder":true,"num":0},"extra":{"_placeholder":true,"num":1}}]'
        asyncio.run(socket._handle_text(header))
        self.assertEqual(received, [])
        self.assertEqual(socket._pending_binary_count, 2)

        asyncio.run(socket._handle_binary(b"first"))
        self.assertEqual(received, [])
        asyncio.run(socket._handle_binary(b"second"))

        self.assertEqual(received, [(
            "history/list/v2",
            {"blob": b"first", "extra": b"second"},
        )])
        self.assertEqual(socket._pending_binary_count, 0)
        self.assertIsNone(socket._pending_binary_header)

    def test_unknown_binary_frame_does_not_create_market_data(self):
        received = []

        async def on_event(event, payload):
            received.append((event, payload))

        socket = QuotexSocket(on_event)
        asyncio.run(socket._handle_binary(b"\\x00\\xff\\x10\\x80"))
        self.assertEqual(received, [])

    def test_malformed_header_does_not_leave_stale_attachment_state(self):
        async def on_event(event, payload):
            raise AssertionError("unexpected event")

        socket = QuotexSocket(on_event)
        socket._pending_binary_header = ["stale"]
        socket._pending_binary_count = 1
        socket._pending_binary_attachments = [b"stale"]
        asyncio.run(socket._handle_text('45x-["history/list/v2",{}]'))
        self.assertIsNone(socket._pending_binary_header)
        self.assertEqual(socket._pending_binary_count, 0)
        self.assertEqual(socket._pending_binary_attachments, [])


if __name__ == "__main__":
    unittest.main()
