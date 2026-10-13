#!/bin/bash
# host-services.sh: make sure the Mac-side services the dev container relies on
# are running. Idempotent; safe to call before every container start (VS Code's
# Dev Containers runs it as initializeCommand, `make dev-up` calls it too).
#
#   USB/IP server  127.0.0.1:3240   phones -> Docker VM (macos/usbip-share)
#   Ollama         127.0.0.1:11436  local models, Metal-accelerated
#
# Both bind to loopback only. Containers reach them as host.docker.internal.
# Logs: ~/.android-editor/logs/
set -u
cd "$(dirname "$0")/.." || exit 1
LOGS="$HOME/.android-editor/logs"; mkdir -p "$LOGS"
listening() { nc -z 127.0.0.1 "$1" >/dev/null 2>&1; }

[ "$(uname -s)" = Darwin ] || { echo "host-services: macOS only (Linux uses docker-compose.linux-usb.yml)"; exit 0; }

# --- USB/IP server
if listening 3240; then
  echo "usbip-share  already running on 127.0.0.1:3240"
else
  BIN=macos/usbip-share/target/release/usbip-share
  if [ ! -x "$BIN" ]; then
    command -v cargo >/dev/null || { echo "usbip-share: needs Rust to build (brew install rust)"; exit 1; }
    echo "usbip-share  building (first run only)"; (cd macos/usbip-share && cargo build --release -q) || exit 1
  fi
  nohup "$BIN" >> "$LOGS/usbip-share.log" 2>&1 &
  sleep 1; listening 3240 && echo "usbip-share  started (log: $LOGS/usbip-share.log)" || { echo "usbip-share  failed to start, see $LOGS/usbip-share.log"; exit 1; }
fi

# --- Ollama (Metal) for the AI tools in the container
PORT="${HOST_OLLAMA_PORT:-11436}"
if listening "$PORT"; then
  echo "ollama       already running on 127.0.0.1:$PORT"
elif command -v ollama >/dev/null; then
  # OLLAMA_NO_CLOUD disables Ollama's own cloud (ollama.com turbo); env -u drops any
  # OLLAMA_API_KEY from this service so there is no credential path to the cloud.
  OLLAMA_HOST="127.0.0.1:$PORT" OLLAMA_CONTEXT_LENGTH="${HOST_OLLAMA_CTX:-16384}" OLLAMA_KEEP_ALIVE=1h \
    OLLAMA_MAX_LOADED_MODELS=1 OLLAMA_NO_CLOUD=1 \
    nohup env -u OLLAMA_API_KEY ollama serve >> "$LOGS/ollama.log" 2>&1 &
  sleep 2; listening "$PORT" && echo "ollama       started on 127.0.0.1:$PORT (log: $LOGS/ollama.log)" || echo "ollama       failed to start, see $LOGS/ollama.log"
else
  echo "ollama       not installed on the Mac (brew install ollama); local models unavailable"
fi
exit 0
