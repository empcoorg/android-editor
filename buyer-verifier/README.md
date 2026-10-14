# buyer-verifier

An Android app (Kotlin) that screens a phone **in the store, before you pay**
and produces a **PDF report** of everything that does not add up — suitable for
presenting to the merchant when a device turns out not to be what was sold.

| Mode | Runs on | What it checks |
|---|---|---|
| Self-check | the candidate device (sideloaded once) | full firmware/software + hardware diff + Play/WhatsApp verdict |
| USB-OTG scan | **your** phone, candidate plugged in over OTG | USB descriptors only — no cooperation needed from the candidate |

## What it checks

- **Firmware & software inconsistencies (the device's original state):**
  marketed name vs build model, brand/manufacturer identity, `user` build +
  `release-keys` + `ro.secure`/`ro.debuggable`, per-partition fingerprint
  divergence (printed with the actual values), reference-design board names,
  build date/user/host, security-patch age.
- **Claimed vs actual:** type in the listing's RAM, storage, screen resolution
  and Android version, plus the IMEI (from `*#06#`) — the report diffs each
  claim against what the device reports, and validates the IMEI checksum.
- **Google Play & WhatsApp approval:** Play Store + Play services presence,
  where to read the Play certification notice, whether the WhatsApp listing is
  reachable, and whether the installed WhatsApp was delivered by Google Play
  (installer `com.android.vending`) — combined into one APPROVED / PARTIAL /
  NOT APPROVED verdict.
- **USB-OTG descriptors (Tier 1):** vendor/product IDs, manufacturer/product
  strings, serial number. Flags reference-design vendor IDs (`0e8d` MediaTek
  and friends), generic strings (`alps`, `unknown`), obviously fake serials and
  product strings that do not match the expected model.

The verdict scoring mirrors `docker/bin/android-authenticity-check` (the
adb-based CLI in the container), so both tools agree.

## The PDF report

**Generate PDF** renders an A4 report — device identity → firmware/software
inconsistencies → claimed vs actual → Play/WhatsApp approval → verdict →
recommended next steps — and opens a share sheet. The only network access is
the optional WhatsApp listing probe; everything else is read locally.

`ReportBuilderTest` fails the build if the report text ever contains
references to system-state changes: the document records the firmware and
software **as received**, full stop.

## Build

```bash
cd buyer-verifier
./gradlew test            # 45 JVM unit tests
./gradlew assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17+ and the Android SDK (platform 35, build-tools 34); point
`local.properties` at the SDK (`sdk.dir=...`) or export `ANDROID_HOME`.

## Status / limitations

- Self-check, rules and report layout are unit-tested; **on-device and
  two-phone USB-OTG behavior still needs a hardware test** (open question 1 in
  `docs/buyer-verifier-app.md`).
- Bluetooth OUI cross-check and the bundled VID/PID signature database from
  the design doc are not implemented yet.
- The Play certification verdict itself is not readable programmatically — the
  report tells you exactly where to read it in the Play Store.
- Modern Android does not let an ordinary app read the IMEI; type it in from
  `*#06#` and the app validates it.
