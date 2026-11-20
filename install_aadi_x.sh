#!/data/data/com.termux/files/usr/bin/bash
set -eu
pkg install -y python curl
mkdir -p "$HOME/aadi-x"
cd "$HOME/aadi-x"
curl -fL --retry 3 -o xstyle_bridge.py https://raw.githubusercontent.com/mawali545/AADI-BOT/main/xstyle_bridge.py
python -m py_compile xstyle_bridge.py
echo
echo "AADI X files installed in: $HOME/aadi-x"
echo "Starting local dashboard. Keep this Termux session open."
echo "Open http://127.0.0.1:8765 in your phone browser."
exec python xstyle_bridge.py
