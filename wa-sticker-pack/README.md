# wa-sticker-pack

A generic WhatsApp **sticker-pack app** template, built from images by
[`docker/bin/wa-sticker-pack`](../docker/bin/wa-sticker-pack). The point: make
your custom stickers survive a wipe or re-flash — keep the built `.apk`, and
after any reset `adb install` it and tap **Add to WhatsApp**.

Don't edit this project to add your stickers — run the tool, which copies this
template, drops in your converted WebP + a generated `contents.json`, and builds
the APK:

```
wa-sticker-pack --name "My Pack" --in ./images --out mypack.apk
```

See [docs/whatsapp-sticker-packs.md](../docs/whatsapp-sticker-packs.md) for the
full story. The app implements WhatsApp's published sticker-app contract
(github.com/WhatsApp/stickers): a `StickerContentProvider` that serves
`assets/contents.json` + the WebP files, and a one-screen `MainActivity` that
sends the `ENABLE_STICKER_PACK` intent. Nothing device- or account-specific is
baked in.

Build it directly (with a pack already in `app/src/main/assets/`):

```
./gradlew :app:assembleDebug            # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebug -PpackAppId=com.stickers.pack.mypack   # unique id
```

Conventions match the rest of the repo: AGP 8.7.3, Kotlin 2.1.21, Gradle 8.14,
compileSdk 35, minSdk 24, no third-party dependencies.
