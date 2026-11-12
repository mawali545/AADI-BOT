#!/data/data/com.termux/files/usr/bin/bash
set -eu

pkg install -y python git
cd "$HOME"
if [ -d "$HOME/AADI-BOT/.git" ]; then
  cd "$HOME/AADI-BOT"
  git pull --ff-only
else
  git clone https://github.com/mawali545/AADI-BOT.git "$HOME/AADI-BOT"
  cd "$HOME/AADI-BOT"
fi

python -m pip install --upgrade setuptools wheel
python -m pip install -r requirements.txt

echo
echo "AADI BOT code and dependencies are ready."
echo "Dashboard can start without a session, but live data requires a valid Quotex session."
if [ -z "${AADI_QUOTEX_SSID:-}" ]; then
  echo "AADI_QUOTEX_SSID is not set in this Termux session."
  echo "Set it privately in Termux before starting the live connection."
  echo "Do not send the session value to anyone or save it in GitHub."
  exec python app.py
fi
exec python app.py
