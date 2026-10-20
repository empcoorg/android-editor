# Getting apps past the "unofficial app" / integrity wall on a rooted phone

Some apps — messengers (WhatsApp and the like), banking, wallets, some games —
refuse to run or to finish sign-up on a rooted device. Two different defences are
at work, and they fail in two different places:

| Where you see it | Defence | What trips it |
|---|---|---|
| **At launch** — a shield / "Download the official app" / "can't verify this app" screen | The app's **own root & tamper detection** (scans for `su`, Magisk, hooking, reads `/proc/version`…) | It can *see* that the device is rooted |
| **At the verify step** — e.g. after entering a phone number and requesting a code | **Google Play Integrity** (server-side), via the Play Integrity API → DroidGuard in Google Play Services | The device can't return a trusted **integrity verdict** |

The genuine, signature-correct app is not the problem. The job is to (a) hide root
from the app and (b) make Play Integrity return a passing verdict. On Magisk this
is a specific stack — and it has one failure mode that wastes more time than all
the others combined.

## The stack (and what each piece is for)

- **Zygisk** — Magisk's app-process injector. **Everything below is a Zygisk
  module or depends on Zygisk.** A Magisk setting, not a module.
- **DenyList** (needs Zygisk) — unmounts Magisk's changes inside chosen apps so
  they can't see root. Hides (a), not (b).
- **PlayIntegrityFix / PlayIntegrityFork** (Zygisk module) — spoofs build props
  inside the DroidGuard process so Play Integrity returns `MEETS_BASIC`/
  `MEETS_DEVICE_INTEGRITY`. Addresses (b) at the software layer.
- **TrickyStore + `keybox.xml`** (needs Zygisk) — fakes the TEE certificate chain
  for the *hardware-attested* path, the only way to lift the verdict to DEVICE
  integrity on a board whose bootloader reports locked/verified. Fragile: leaked
  keyboxes get revoked. See [play-integrity-hardware-avb.md](play-integrity-hardware-avb.md).
- **Shamiko** (optional, Zygisk module) — stronger, "allowlist-style" hiding that
  reads the DenyList but replaces Magisk's own enforcement.

## The #1 silent failure: Zygisk is off

**Symptom:** apps that used to work start showing the wall again "for no reason",
often right after the Magisk app was **updated, repaired or reinstalled**. The
modules are all still listed and enabled, so it looks fine.

**Cause:** the Magisk app's repair/update flow resets **Zygisk to off** (its
default). With Zygisk off, PlayIntegrityFix, the DenyList and Shamiko **all do
nothing** — silently. Nothing is spoofed, nothing is hidden, so both (a) and (b)
fail.

**Check it first, every time:**

```
adb -s SERIAL shell su -c "magisk --sqlite \"SELECT * FROM settings\""
#   key=zygisk|value=0   <-  this is the bug
```

Safe-mode is a related trap: an unstable boot makes Magisk **auto-disable every
module** (drops a `disable` file in each `/data/adb/modules/*/`). `ls
/data/adb/modules/*/disable` should list nothing; `rm` them to re-enable.

## The fix

1. **Enable Zygisk** (reboot required — Zygisk only activates on boot):
   ```
   magisk --sqlite "REPLACE INTO settings (key,value) VALUES('zygisk',1)"
   ```
2. **DenyList enforcement:** ON normally; **OFF if Shamiko is installed**
   (Shamiko hides from the list without Magisk's enforcement):
   ```
   magisk --sqlite "REPLACE INTO settings (key,value) VALUES('denylist',1)"   # 0 with Shamiko
   ```
3. **Add the app and the Play Store to the DenyList** so they can't see root:
   ```
   magisk --denylist add <app.package>
   magisk --denylist add com.android.vending
   ```
   **Do NOT add Google Play Services** (`com.google.android.gms`).
   PlayIntegrityFix injects *into* its DroidGuard process; denylisting GMS
   unmounts the spoof. PIF removes GMS from the DenyList by itself for this
   reason — if you see your GMS entries vanish after a boot, that's expected and
   correct.
4. **If TrickyStore is present,** add the app to its target list so it gets the
   keybox attestation too:
   ```
   grep -qxF <app.package> /data/adb/tricky_store/target.txt || echo <app.package> >> /data/adb/tricky_store/target.txt
   ```
5. **Reboot.** Then launch the app — it should show its normal welcome/login.

The `android-integrity-fix` tool does all of the above idempotently, picks the
right DenyList-enforcement value for whether Shamiko is present, and never
denylists GMS:

```
android-integrity-fix -s SERIAL --check  com.whatsapp com.whatsapp.w4b   # report only
android-integrity-fix -s SERIAL --reboot com.whatsapp com.whatsapp.w4b   # apply + reboot
android-integrity-fix -s SERIAL --reboot --verify com.whatsapp           # + screenshot proof
```

## Verifying it worked

- **Launch check (the real test):** open the app. The tamper wall gone and a
  normal welcome/login screen = (a) solved.
- **Attestation actually ran:** while launching the app, DroidGuard should start
  and the Play Integrity API should be invoked —
  ```
  adb -s SERIAL shell su -c "logcat -d" | grep -iE "droidguard|play.integrity"
  #  ... Start proc ...com.google.android.gms.unstable ... DroidGuardService
  #  ... play.integrity.api ...
  ```
  Seeing DroidGuard spawn when the app opens means the app is going through Play
  Integrity (not just refusing up front).
- **Verdict level:** to see `MEETS_BASIC` vs `MEETS_DEVICE_INTEGRITY` directly,
  run a "Play Integrity API Checker" app on the device. Most messengers only need
  up to device integrity, and the launch wall is beaten by root-hiding regardless
  of the verdict.

## When it still fails at the verify step

- The **verdict is capped at BASIC** on a board that reports a locked/verified
  bootloader while being rooted — a contradiction Google's servers detect. This
  is a *hardware-attestation* ceiling that build-prop spoofing can't lift; the
  only lever is TrickyStore + a *valid, unrevoked* `keybox.xml`. Full analysis:
  [play-integrity-hardware-avb.md](play-integrity-hardware-avb.md).
- **Add Shamiko** for stronger hiding (then set DenyList enforcement OFF), for
  apps that detect root past a plain DenyList.
- **Clear the app's data** if it cached a failed/blocked state before the fix
  (`pm clear <app.package>`), then relaunch. Re-grant any runtime permissions
  afterwards — `pm clear` resets them.
- Confirm **GMS is up to date** and the device has Google Play Services at all;
  Play Integrity needs a working GMS. (`android-authenticity-check` reports this.)
