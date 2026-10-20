# Play Integrity on a rooted board with hardware AVB attestation (MediaTek example)

Example case: `alps` MediaTek reference board, Android 12, Magisk 30.7.

> **Update (later session):** TrickyStore + `keybox.xml` was subsequently
> deployed on this class of board. The practical top cause of apps failing here
> turned out to be **Zygisk silently switched off** by a Magisk app repair/update
> — which disables PlayIntegrityFix, the DenyList and Shamiko all at once. With
> Zygisk back on and the DenyList hiding the app, the launch-time tamper wall
> clears and the Play Integrity attestation actually runs; the hardware *verdict*
> can still cap at BASIC as analysed below. Step-by-step procedure and the
> `android-integrity-fix` tool: [app-integrity-rooted-fix.md](app-integrity-rooted-fix.md).

## Goal
Restore `MEETS_DEVICE_INTEGRITY` for WhatsApp after rooting.

## What was done (and verified working)
- **PlayIntegrityFork v18** (osm0sis) installed + Zygisk ON — confirmed injected into
  the DroidGuard process (`com.google.android.gms.unstable`).
- **autopif4** generated a valid Pixel 8a profile → `custom.pif.prop`.
- **real_fingerprint** module restores the true Android 12 fingerprint (vendor ROM
  shipped a fake Android 6 one).
- PIF build spoof **confirmed in logcat**: `<device model> -> Pixel 8a`, model/fingerprint/
  security-patch/api_level all successfully spoofed at the DroidGuard API layer.

## Result: still `MEETS_BASIC_INTEGRITY` — root cause identified
PIF works perfectly at the **software/build-prop layer**, but Google's Play Integrity
verdict is capped by **hardware key attestation** (server-side), which PIF cannot spoof.

The blocking evidence:
```
ro.boot.verifiedbootstate   = green
ro.boot.flash.locked        = 1
ro.boot.vbmeta.device_state = locked
ro.boot.veritymode          = enforcing
```
- These are **real AVB values**, not fake props: `getprop` == `resetprop`, real
  `vbmeta`/`vbmeta_system`/`vbmeta_vendor` partitions exist, and a hardware
  **Gatekeeper HAL** (`gatekeeper@1.0-impl`, TEE-backed) is loaded and enforcing.
- So the device reports a **genuinely locked, verified bootloader** *while being
  Magisk-rooted*. That is a logically impossible state for a real device, and
  Google's server-side attestation detects the contradiction → caps at
  `MEETS_BASIC_INTEGRITY`.

In short: this reference board ships a factory ROM that reports `green`/`locked`
bootloader props, but the bootloader is physically unlockable (hence root). The
software build spoof (PIF) succeeds; the **hardware-attested** bootloader state
cannot be faked with build props.

## The only remaining path (not attempted): TrickyStore + keybox
`MEETS_DEVICE_INTEGRITY` on a hardware-attested device requires spoofing the TEE
certificate chain itself via **5ec1cff/TrickyStore** plus a valid `keybox.xml`
(signed cert chain attesting a locked, unmodified bootloader). Caveats from the PIF
maintainers themselves: keyboxes are leaked, scarce, and **quickly revoked** by
Google's crawlers; it's a deep rabbit hole. Not worth it for WhatsApp, which per
the docs "very likely" only needs up to device integrity — and even that is blocked
here by the locked+rooted contradiction.

## Conclusion
`MEETS_DEVICE_INTEGRITY` is **not achievable with PIF alone** on this device due to
hardware AVB attestation on a rooted board. Options: (a) accept BASIC, (b) attempt
TrickyStore + keybox (fragile, likely-revoked), or (c) use a device whose bootloader
is honestly unlocked so software-layer spoofing suffices.
