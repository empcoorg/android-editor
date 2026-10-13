# USB/IP flap: phone keeps dropping adb (diagnosed 2026-10-13)

## Symptom
The phone is visible to `lsusb` inside the container (`0e8d:201c`) but `adb
devices` is empty or intermittent. The `usbip` sidecar log loops:
`attached 0-5-3 …` then `attached 0-2-3 …`, and the device node number keeps
incrementing (`023` → `024`).

## Ruling out a hijacker (do this first)
Check whether two adb servers or the Mac are fighting over the phone:
- Mac host adb: `adb devices` (may be absent entirely on a Mac-only setup).
- Release list: `cat ~/.usbip-share/exclude` (empty = nothing released to the Mac).
- Container adb servers: `pgrep -af adb` (should be exactly one).
- If only one owner and the release list is empty, it is **not** contention.

## Root cause (confirmed via the usbip-share server log)
The Mac-side server logs to its scratchpad file (find it with
`lsof -p $(pgrep -f usbip-share) | grep usbip-share.log`). The tell-tale:
```
… ep 0x01 IN len 512 -> status -5 actual 0   # USB I/O error
INFO unplugged 0e8d:201c … (0-2-3)           # phone drops at the SOURCE
INFO dropped … after unplug
INFO exported … as bus id 0-2-3              # re-appears ~3-4s later
INFO imported … (0-2-3)                      # sidecar re-attaches
… SET_CONFIGURATION 1                        # re-enumerates, adb restarts
```
A `status -5` (I/O error) → `unplugged` → re-export cycle means the **phone is
resetting its own USB port** (rebooting, or an unstable USB stack — common on
cheap MediaTek boards, especially rooted). Because the drop happens before
USB/IP, no container/Magisk config fixes it.

## What actually helps
1. **Different cable + a direct USB port** (no hub) — removes marginal-cable I/O
   errors. Highest-value first try.
2. **Work in bursts during stable windows.** They last ~60–90s between resets —
   long enough for a command batch (a full SPIC verdict was captured this way).
   Loop-poll `adb get-state` until `device`, then run everything in one shot.
3. For long/multi-reboot operations (Magisk module installs), prefer driving the
   **on-phone Magisk app UI** over remote orchestration, so a mid-step USB reset
   can't corrupt the sequence.
