#!/bin/sh
# Keep every device exported by the Mac-side usbip-share server attached to
# this VM. Attached devices appear under /dev/bus/usb for every privileged
# container that bind-mounts it (the android-editor dev shell). Once a device
# is attached the server stops listing it, so this never double-attaches;
# when the server drops a device (unplug, mode change) vhci_hcd removes it
# and the replacement is picked up on the next pass.
SERVER="${USBIP_SERVER:-host.docker.internal}"
INTERVAL="${USBIP_INTERVAL:-2}"

if [ ! -d /sys/devices/platform/vhci_hcd.0 ]; then
  echo "usbip-client: this kernel has no vhci_hcd; USB/IP import is unavailable" >&2
  exit 1
fi
echo "usbip-client: importing devices from $SERVER every ${INTERVAL}s"

# devtmpfs creates imported devices as 0664 root:root; the dev shell runs as an
# unprivileged user, so make the nodes world read/write (there is no udev here).
open_nodes() { [ -d /dev/bus/usb ] && chmod 666 /dev/bus/usb/*/* 2>/dev/null; }

# Imported devices live on TCP sockets that belong to *this* container's
# network namespace. If the container goes away while they are attached, the
# kernel keeps the ports but nothing can ever reach those sockets again, and
# any program touching the stale devices hangs. So: start from a clean slate,
# and detach everything on the way out.
detach_all() {
  for p in $(usbip port 2>/dev/null | awk '/^Port [0-9]+:/ { sub(":", "", $2); print $2 }'); do
    usbip detach -p "$p" >/dev/null 2>&1 && echo "usbip-client: detached port $p"
  done
}
detach_all
trap 'echo "usbip-client: stopping, detaching all devices"; detach_all; exit 0' INT TERM

reachable=""
while :; do
  if out=$(usbip list -r "$SERVER" 2>&1); then
    [ -z "$reachable" ] && { echo "usbip-client: server $SERVER reachable"; reachable=1; }
    echo "$out" | awk '$1 ~ /^[0-9]+-[0-9]+-[0-9]+:$/ { sub(":", "", $1); print $1 }' | while read -r busid; do
      desc=$(echo "$out" | awk -v b="$busid:" '$1 == b { $1 = ""; print }' | sed 's/^ *//')
      if usbip attach -r "$SERVER" -b "$busid" >/dev/null 2>&1; then
        sleep 1; open_nodes
        echo "usbip-client: attached $busid $desc"
      else
        echo "usbip-client: attach of $busid failed, will retry" >&2
      fi
    done
  else
    [ -n "$reachable" ] || [ -z "$first" ] && echo "usbip-client: waiting for $SERVER (is usbip-share running on the Mac?)"
    first=1; reachable=""
  fi
  open_nodes
  sleep "$INTERVAL" &
  wait $!
done
