# Buyer-side Android authenticity verifier (QUEUED)

**Status:** queued — start **after** the connected Example Phone WhatsApp / Play-Integrity
fix. Do not begin implementation yet.

## Goal
A free, downloadable Android app a shopper runs on **their own phone** to
sanity-check a phone they're about to buy, **in the store, before paying**.
Connection: **USB-OTG cable** and/or **Bluetooth**. No root, no laptop, ideally
no internet required.

## The hard constraint (drives a two-tier design)
The buyer's phone **cannot** read another phone's `getprop`, Play-Integrity
verdict, or IMEI programmatically **unless the candidate cooperates** (runs code
or grants access). A candidate in a store won't have your app and won't have USB
debugging on. So the app splits in two:

### Tier 1 — buyer-phone only (the shippable MVP; no candidate cooperation)
- **USB Host API** (USB-OTG): read the candidate's USB **descriptors** — Vendor
  ID, Product ID, manufacturer/product strings, serial. Needs only USB-host
  declaration + the user granting the accessory.
  - Red flags: generic MediaTek/Spreadtrum reference VIDs (`0e8d`, `spd*`),
    manufacturer strings like `alps` / `unknown` / `Android`, obviously fake
    serials (all-same-digit), product strings that don't match the marketed model.
- **Bluetooth**: read the advertised device name + Bluetooth MAC; derive the
  **OUI** (first 3 bytes) and cross-check against the marketed brand. A brand/MAC
  mismatch (e.g. a "flagship" name over a MediaTek reference OUI) is a flag.
- **Bundled offline signature DB**: known-genuine VID/PID + OUI ranges vs known
  reference-board/clone signatures. Ship it in the APK; refresh periodically.
  **No network needed.**
- Output: red / amber / green screener + "what to double-check with the seller."

This directly reuses the heuristics already validated in
`docker/bin/android-authenticity-check` (generic-board detection,
manufacturer-string mismatch, VID `0e8d`, IMEI Luhn) — ported from `adb`/`getprop`
to the **USB-OTG + Bluetooth descriptor** layer.

### Tier 2 — deep integrity (needs candidate cooperation)
For checks that must run *on* the candidate (Play-Integrity verdict, build
-fingerprint consistency, root/spoofer detection, IMEI Luhn):
- **Option A:** buyer temporarily sideloads a tiny helper APK onto the candidate
  over USB-OTG (candidate must allow "install unknown apps" — the friction point),
  which runs the SPIC-style check and shows a verdict.
- **Option B:** ship two apps — the Tier-1 buyer app + an optional "self-check"
  APK for Tier 2.
- Realistically Tier 2 is a "high-value purchase / power user" path; Tier 1 is
  the everyday screener.

## What we already have to port
- `docker/bin/android-authenticity-check` — the heuristic rules + verdict scoring
  (build type, signing keys, generic board, IMEI Luhn, patch age, spoofer apps).
  The **rules transfer**; the **data source** changes from adb to USB-OTG/BT
  descriptors for Tier 1.

## Open questions / next steps (when we pick this up)
1. **Main technical risk:** confirm USB-OTG host reliably reads a
   *phone-as-accessory* across OEMs — needs a real two-phone test.
2. Build the signature DB (VID/PID + OUI): seed from devices we've already seen +
   public USB ID databases.
3. Decide distribution: F-Droid / Play / direct APK.
4. UI: single-screen scan → verdict.
5. Decide whether this doc stays in the public repo or moves to a private one.
