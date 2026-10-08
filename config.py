import os

WSS_URL = os.getenv(
    "AADI_WSS_URL",
    "wss://quotex.io/socket.io/?EIO=4&transport=websocket",
)
SSID = os.getenv("AADI_QUOTEX_SSID", "")
IS_DEMO = os.getenv("AADI_IS_DEMO", "0") == "1"
ASSET = os.getenv("AADI_ASSET", "EURUSD_otc")
TIMEFRAME = int(os.getenv("AADI_TIMEFRAME", "60"))
HOST = os.getenv("AADI_HOST", "0.0.0.0")
PORT = int(os.getenv("AADI_PORT", "8000"))

EMA_FAST = int(os.getenv("AADI_EMA_FAST", "9"))
EMA_SLOW = int(os.getenv("AADI_EMA_SLOW", "21"))
RSI_PERIOD = int(os.getenv("AADI_RSI_PERIOD", "14"))
MIN_CONFLUENCE = int(os.getenv("AADI_MIN_CONFLUENCE", "3"))
