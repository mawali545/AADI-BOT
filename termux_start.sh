#!/data/data/com.termux/files/usr/bin/bash
set -eu

cd "$(dirname "$0")"

echo "AADI BOT — Termux launcher"
echo "This launcher does not modify B-test or any backup."
echo "It uses a current session you provide locally; it does not bypass login."
echo

if ! command -v python >/dev/null 2>&1; then
  echo "ERROR: Python is not installed in Termux."
  exit 1
fi

echo "[1/3] Installing the direct-feed requirements..."
if ! python -m pip install -r requirements-quotex-ws.txt; then
  echo
  echo "INSTALL_FAILED: curl_cffi could not be installed in this Termux environment."
  echo "No files were deleted. Do not paste session cookies into chat."
  echo "The current direct WebSocket transport needs a compatible curl_cffi build."
  exit 2
fi

echo
echo "[2/3] Enter your current Quotex session value locally."
echo "Input stays hidden. Do not share it or commit it to GitHub."
printf "Quotex SSID: "
stty -echo
IFS= read -r AADI_QUOTEX_SSID
stty echo
printf "\n"
if [ -z "$AADI_QUOTEX_SSID" ]; then
  echo "ERROR: Empty session value; not starting."
  exit 3
fi

export AADI_QUOTEX_SSID
export AADI_FEED_PROVIDER=quotex
export AADI_ASSET="${AADI_ASSET:-EURUSD_otc}"
export AADI_TIMEFRAME="${AADI_TIMEFRAME:-60}"

echo "[3/3] Starting AADI BOT..."
echo "Open http://127.0.0.1:8000 in your phone browser."
echo "Only trust signals after status is LIVE and price matches Quotex."
python app.py
