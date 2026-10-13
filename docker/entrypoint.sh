#!/bin/bash
set -e

# With raw USB access (privileged + /dev/bus/usb), make device nodes usable by
# the unprivileged dev user. There is no udev daemon in here to do it for us.
if [ -d /dev/bus/usb ]; then
  sudo -n chmod 666 /dev/bus/usb/*/* 2>/dev/null || true
fi

# Adopt the host's adb key when it is mounted (docker-compose.mac-usb.yml) and we
# have none yet, so phones that already trust the host trust this shell too.
if [ -f /run/host-android/adbkey ] && [ ! -f "$HOME/.android/adbkey" ]; then
  mkdir -p "$HOME/.android" && chmod 700 "$HOME/.android"
  cp /run/host-android/adbkey "$HOME/.android/adbkey" && chmod 600 "$HOME/.android/adbkey"
  [ -f /run/host-android/adbkey.pub ] && cp /run/host-android/adbkey.pub "$HOME/.android/adbkey.pub"
fi

# One adb server for the whole container, listening on all interfaces so the
# Mac can use the phones too (published on 127.0.0.1:5038, see compose).
if [ -d /dev/bus/usb ]; then
  adb -a start-server >/dev/null 2>&1 || true
fi

# AI tool configs follow .env: regenerate them on every start (offline-safe).
rm -f "$HOME/.aider.conf.yml" "$HOME/.aider.model.settings.yml"
ai-setup --config-only >/dev/null 2>&1 || true
vscode-ai-config >/dev/null 2>&1 || true

if [ -t 1 ] && [ "$#" -eq 1 ] && [ "$1" = "bash" ]; then
  cat <<BANNER

  android-editor
  models    ${OPENCODE_MODEL#ollama/} (default), ${OPENCODE_FALLBACK_MODEL#ollama/} (@reasoner)
  opencode          AI agent in this terminal (Cline / Roo Code: VS Code Dev Container)
  android-doctor    tools, models and attached phones
  android-full-backup -s <serial>    back up a phone before rooting
  Magisk APK        \$MAGISK_APK

BANNER
fi

exec "$@"
