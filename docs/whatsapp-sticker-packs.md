# Keep your WhatsApp stickers across a wipe or re-flash

Custom WhatsApp stickers are easy to lose: wipe the phone, switch ROMs, or
reinstall WhatsApp and your personal stickers are gone, and re-adding a big set
by hand is tedious. `wa-sticker-pack` turns a folder of images into a **standalone
sticker-pack app** (an `.apk`) that registers with WhatsApp. Keep that one file,
and after any reset you just `adb install` it and tap **Add to WhatsApp** — no
re-doing the work.

## Why a standalone pack app (not WhatsApp's in-app stickers)

A sticker pack installed this way lives in **its own app**, through WhatsApp's
public sticker API (a `ContentProvider` + the `ENABLE_STICKER_PACK` intent). That
is what makes it durable:

- It does **not** depend on your WhatsApp account, so it survives a logout, a
  re-registration, a new number, or a reinstall.
- It is a single `.apk` you can back up and re-install anywhere — the exact thing
  you want when you flash often.
- Backups stay clean: you can exclude WhatsApp's own account data from a device
  backup (see `android-full-backup --exclude-data`) and still keep your stickers,
  because they are in the pack app, not in WhatsApp.

## Use it

```
# in the dev container (ImageMagick + jq + JDK + SDK are already there)
wa-sticker-pack --name "My Cats" --publisher "Me" --in ./cat-pics --out mycats.apk

adb install -r mycats.apk      # put it on the phone
# open "My Cats" on the phone, tap "Add to WhatsApp"
```

- **Input:** `--in` a folder of `.png/.jpg/.jpeg/.webp/.gif/.heic`. Each image is
  squared to **512×512 WebP ≤100 KB** automatically (the first also becomes the
  96×96 tray icon). WhatsApp allows **3–30 stickers** per pack; with more than 30,
  run the tool again for a second pack.
- **Output:** `--out` the `.apk` (default `<name>.apk`). Also `--emoji` to set the
  suggested emoji, and `--app-id` to give two packs distinct package names so they
  coexist on one device (default `com.stickers.pack.<name>`).
- Animated stickers are flattened to their first frame in this version.

## Preserving them

The durable artifacts are **the `.apk`** (re-install → stickers back) and **your
source images**. Store them wherever you keep backups. There is nothing to
"restore into" WhatsApp — installing the pack app and tapping *Add to WhatsApp*
is the whole restore.

## How it works

`wa-sticker-pack` builds the generic app under [`wa-sticker-pack/`](../wa-sticker-pack):

- **ImageMagick** squares + pads each image to a transparent 512×512 canvas and
  encodes WebP under WhatsApp's size limit (`webp:target-size`).
- A `contents.json` describing the pack and a `StickerContentProvider` serve the
  stickers to WhatsApp; `MainActivity` sends the `ENABLE_STICKER_PACK` intent.
- The project follows WhatsApp's published sticker-app contract
  (github.com/WhatsApp/stickers). Nothing device- or account-specific is baked in.

The build uses the project's gradle wrapper and your Android SDK; it produces a
debug-signed APK so it installs straight from `adb` with no Play Store or signing
setup.
