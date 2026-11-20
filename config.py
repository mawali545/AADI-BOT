import os

WSS_URL = os.getenv(
    "AADI_WSS_URL",
    "wss://ws2.qxbroker.com/socket.io/?EIO=3&transport=websocket",
)
WS_ORIGIN = os.getenv("AADI_WS_ORIGIN", "https://qxbroker.com")
SSID = os.getenv("AADI_QUOTEX_SSID", "")
ASSET = os.getenv("AADI_ASSET", "EURUSD_otc")
TIMEFRAME = int(os.getenv("AADI_TIMEFRAME", "60"))
HISTORY_CANDLES = int(os.getenv("AADI_HISTORY_CANDLES", "300"))
HOST = os.getenv("AADI_HOST", "0.0.0.0")
PORT = int(os.getenv("AADI_PORT", "8000"))

EMA_FAST = int(os.getenv("AADI_EMA_FAST", "9"))
EMA_SLOW = int(os.getenv("AADI_EMA_SLOW", "21"))
RSI_PERIOD = int(os.getenv("AADI_RSI_PERIOD", "14"))
MIN_CONFLUENCE = int(os.getenv("AADI_MIN_CONFLUENCE", "3"))
STALE_AFTER = float(os.getenv("AADI_STALE_AFTER", "5"))
