# WhatsApp verification blocked by NO mobile data

## Status: diagnosed 2026-10-13 — the fix is physical/carrier-side, not software

## The finding that reframes the task
WhatsApp cannot deliver a verification code without a working internet
connection. **The phone in this case had no usable mobile data**, which is a
sufficient and independent cause of the verification failure — regardless of
Play Integrity, PIF, or any Magisk module.

Evidence captured over adb (serials and addresses shown as placeholders):
- `ping -c1 8.8.8.8` → **FAIL** (raw IP, no DNS involved) — 3/3 attempts.
- `ping whatsapp.com` → FAIL.
- `ip route` shows only `10.0.0.0/8 dev ccmni0` — **no default route**
  (`0.0.0.0/0`) to the real internet.
- `net.dns1` / `net.dns2` → **empty**.
- `settings get global mobile_data` = `1` (enabled), `airplane_mode_on` = 0,
  `data_roaming` = 0.
- Two SIMs, both `LOADED`, local operator. `ccmni0` (MediaTek mobile data) had a
  carrier-private `10/8` address, so the **PDN comes up and the carrier assigns
  an address**, but traffic never leaves the carrier network.

### Interpretation
IP assigned + ping-to-raw-IP fails + no default route + no DNS = the classic
signature of **no data plan / no credit, or a dead/misconfigured APN**, or a
carrier-side block. It is NOT a Play Integrity problem and NOT a DNS problem
(a raw-IP ping fails too).

## What was tried
- Airplane-mode on/off cycle to force re-registration → after the toggle the
  data bearer did **not** come back: `gsm.network.type` = `Unknown`, no operator
  name, `ccmni0` lost its IP. So the radio is in a degraded/no-service state.

## Conclusion
The verification-code step needs mobile data (or Wi-Fi). Until the phone has a
working connection, no on-device Play Integrity / root / module work can make
WhatsApp send a code. Next actions are physical/carrier-side, not software.

## Recommended next steps (in order)
1. **Check the SIM data plan / credit** with the carrier. If the SIM is
   pay-as-you-go with no data bundle, that alone explains it.
2. **Verify the APN** is correct for the carrier (Settings > Mobile network >
   Access point names) and select the carrier's default data APN.
3. **Try Wi-Fi instead** — turn Wi-Fi on and join a known-good network. If
   WhatsApp then sends the code, the whole Play Integrity investigation was
   chasing the wrong symptom.
4. Reseat SIM / try the other SIM slot; confirm mobile data works in a browser
   first.

Once internet works, re-test WhatsApp verification. Only if it STILL fails with
a network-independent error (e.g. "you need the official app", ban, or a
Play-Integrity style block) does the `docs/play-integrity-hardware-avb.md`
analysis (hardware AVB caps at MEETS_BASIC_INTEGRITY; only TrickyStore+keybox
remains) become the next thing to tackle.
