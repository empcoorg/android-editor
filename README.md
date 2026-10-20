# android-editor

A Docker dev container for **building Android apps** and **rooting Android
phones**, with a local AI coding agent. On a Mac, phones plugged into USB reach
the container over USB/IP.

| Area | In the container |
|---|---|
| Build apps | OpenJDK 17 and 11, Maven, Android SDK (cmdline-tools, platform-tools, build-tools 36 and 33.0.2, platforms 36 and 33), Gradle 8.14 as `gradle` and Gradle 7.5.1 as `gradle7`. Gradle downloads any other SDK version a project asks for. NDK optional (`INSTALL_NDK=1`). |
| Root phones | adb, fastboot, Magisk APK (`$MAGISK_APK`) + `magiskboot`, payload-dumper-go, mkbootimg / unpack_bootimg / repack_bootimg, avbtool, simg2img / img2simg, lpunpack / lpmake / lpdump (super.img), sdat2img, ext4 / EROFS / F2FS tools, binwalk, dtc, mtkclient (`mtk`, MediaTek), edl (Qualcomm), heimdall (Samsung), `android-full-backup`, `wa-sticker-pack` |
| Shell | wget, curl, git, nano, vim, tmux, htop, rsync, jq, ripgrep, 7z, zstd, lz4, brotli, hexdump, ping and the usual coreutils |
| AI | OpenCode in the terminal; Cline and Roo Code in VS Code. All use the same two local models. |

## The two models

Served by Ollama on the Mac (Metal-accelerated, `127.0.0.1:11436`), not inside
Docker:

| Role | Model | Used by |
|---|---|---|
| Default | `qwen3:14b` (9.3 GB) | OpenCode's agent, Cline Act **and Plan**, every Roo Code mode |
| Second opinion | `deepseek-r1:14b-qwen-distill-q8_0` (15 GB) | OpenCode's `@reasoner` subagent only, called after two failures or before risky flashing |

**The default model must support tool calling**, or the agents print commands
instead of running them and nothing happens. Measured here on 2026-10-12:

| Model | Runs commands | Speed |
|---|---|---|
| `qwen3:14b` | yes | 17.7 tok/s |
| `deepseek-r1:14b-qwen-distill-q8_0` | no | 14.9 tok/s |
| `qwen2.5-coder:32b-instruct-q4_K_M` | no | 0.2 tok/s (only 17.7 of its 24.6 GB fits on this Mac's GPU) |

R1 is fine where it sits: the `@reasoner` subagent is read-only by design and
only returns prose. Anything that reads files or runs commands, Cline's Plan
mode included, needs the tool-capable default; `VSCODE_PLAN_MODEL` overrides
that for the VS Code extensions if you want a different one.

Both come from `.env` (`OPENCODE_MODEL`, `OPENCODE_FALLBACK_MODEL`); the tool
configs are regenerated from it whenever the container starts. `make models`
downloads whichever is missing.

Both fit comfortably on a 24 GB Mac alongside Docker. A 32B model does not: it
spills onto the CPU and drops to a fraction of a token per second.

## Quick start (macOS)

```bash
brew install ollama rust          # once: model server, and the toolchain for the USB/IP server
cp .env.example .env              # once
make build                        # once, ~20 minutes
make dev-up                       # starts the Mac-side services, then the container
make models                       # once: downloads the two models (~36 GB)
make attach                       # a shell in the container
```

Inside: `android-doctor` lists tools, models and attached phones; `opencode`
starts the agent in the current directory. Your projects live in
`./workspace`, mounted at `/workspace`.

## VS Code with Cline and Roo Code

Open this folder in VS Code (with the *Dev Containers* extension) and run
**Dev Containers: Reopen in Container**. That:

1. runs `macos/host-services.sh` on the Mac, starting the USB/IP server and
   Ollama if they are not running;
2. starts or reuses the same `android-editor-dev-1` container `make attach` uses;
3. installs Cline and Roo Code inside it, so they and the integrated terminal
   see the phones exactly like `make attach` does;
4. points both at the two models (`vscode-ai-config`).

Cline is only re-seeded when `.env` changes, so choices made in its settings
stick. Roo Code re-imports its two profiles on every start; profiles you add
yourself are kept. Closing VS Code leaves the container running; `make
dev-down` stops it.

### Ports

All on the Mac's loopback interface; containers reach the Mac as
`host.docker.internal`.

| Port | Service | Used by |
|---|---|---|
| 3240 | USB/IP server | the `usbip` sidecar, which imports phones into the Docker VM |
| 11436 | Ollama | OpenCode, Cline, Roo Code |
| 5038 | the container's adb server | tools on the Mac: `adb -P 5038 devices`, scrcpy, Android Studio |

## Remote model on RunPod (optional)

The agent model can run on a RunPod GPU instead of locally, while the phone
stays on local USB. A pod runs vLLM serving an OpenAI-compatible endpoint;
OpenCode, Cline and Roo point at it. `.env` controls the switch:

```
LLM_BACKEND=runpod            # or "local" for the Mac's Ollama
RUNPOD_LLM_URL=https://<podid>-8000.proxy.runpod.net/v1
RUNPOD_LLM_KEY=sk-...         # protects the vLLM endpoint
RUNPOD_LLM_MODEL=qwen3-32b
RUNPOD_POD_ID=<podid>
RUNPOD_API_KEY=rpa_...        # RunPod account key, for runpodctl (start/stop the pod)
```

Current pod runs **Qwen3-32B** (bf16) on an RTX PRO 6000 (96 GB). It calls
tools reliably in auto mode; it spends a few hundred reasoning tokens per step,
so a step takes ~15-20 s.

**SSH into the pod** (your registered key authenticates automatically):

```
ssh $(grep -m1 '^RUNPOD_SSH=' .env | cut -d= -f2-)
# currently: ssh <podid>-<port>@ssh.runpod.io   (store the full command in RUNPOD_SSH in .env)
```

RunPod's proxy needs an interactive terminal; for a one-off command use
`ssh -t <user>@ssh.runpod.io '<cmd>'`. The 65 GB model is cached on a network
volume (`android-editor-models`, mounted at `/workspace`, `HF_HOME` there), so
restarts skip the download. The volume costs ~$7/month until deleted.

**Cost control — the pod bills ~$2.49/hr while running.** Stop it when idle.

First authenticate `runpodctl` once (your RunPod API key lives in `.env` as
`RUNPOD_API_KEY`; read it from there so it is never typed or logged):

```bash
# one-time: authenticate runpodctl from the key already in .env
runpodctl config --apiKey "$(grep -m1 '^RUNPOD_API_KEY=' .env | cut -d= -f2-)"
# also registers an SSH key at ~/.runpod/ssh/runpodctl-ssh-key for the account
```

Then start/stop the pod and flip the agent between backends:

```bash
make runpod-status   # which backend the agent uses + live pod state (proves auth works)
make runpod-down     # switch agent back to local Ollama AND stop the pod (billing pauses, disk kept)
make runpod-up       # start the pod and switch the agent back to it
```

To confirm the agent is really pointed at the GPU, the container's OpenCode
config (`~/.config/opencode/opencode.json`) lists `runpod` as the provider, and
`curl`ing the endpoint from inside the container returns the served model:

```bash
docker compose -f docker-compose.yml -f docker-compose.mac-usb.yml exec dev \
  curl -fsS https://<podid>-8000.proxy.runpod.net/v1/models -H "Authorization: Bearer $RUNPOD_LLM_KEY"
```

What you type into the agent leaves the Mac for the RunPod GPU; the phone work
itself stays entirely local.

### Automatic backend selection

`bin/ai-backend` picks between local Ollama and the RunPod pod by **model
capability**, and manages the pod to control cost. It runs on the **host**
(needs `runpodctl`, `docker`, `curl`, `jq`):

```bash
make ai-backend            # show backend, models, pod state, idle timer
make ai-pick TIER=large    # default|large — starts the pod only if a bigger model is needed
make ai-watch              # idle watcher: stops the pod after IDLE_MINUTES (default 15) of no activity
make ai-beat               # record activity (resets the idle timer)
bin/ai-backend recommend "flash boot.img"   # suggest which AI tool fits a task
```

Policy: use **local** when it fits the task tier; when a task needs a bigger
model than local can run well and the pod offers one, **start the pod on
demand**, then **stop it after the idle timeout** so billing pauses. Tune with
`IDLE_MINUTES` and `LOCAL_MAX_B` (params/B local is trusted to run well); set
`AI_BACKEND_DRYRUN=1` to preview a decision without changing anything.

The `recommend` subcommand routes by task: terminal/adb/fastboot/flash work →
**OpenCode** (autonomous), interactive coding → **Cline**, mode-specialized
(architect/debug) → **Roo Code**. Runtime auto-hand-off between a VS Code
extension and a CLI is not technically possible, so this is a recommender, not
an automatic switch.

## Phones over USB

Docker Desktop cannot pass USB devices to containers, but its VM kernel has the
USB/IP client built in. `macos/usbip-share` (Rust, built on first run) is the
USB/IP server on the Mac; it exports phones by vendor id, and the `usbip`
sidecar imports them into the VM. Mode changes (adb ↔ fastboot ↔ BROM) are
picked up within two seconds.

- **One owner at a time.** A USB interface can be claimed by the Mac or the
  container, not both. The Mac's own adb server grabs any adb-mode phone it
  sees; `adb kill-server` on the Mac frees it.
- **Leaving a phone to the Mac:** `make usb-release DEV=<serial>`, and
  `make usb-reclaim DEV=<serial>` to take it back. `make usb-status` lists
  what is released.
- **Works from the container:** adb and fastboot. Samsung download mode and
  Qualcomm EDL use vendor-specific USB classes that macOS does not claim, so
  they should pass through too; untested.
- **MediaTek BROM/preloader does not:** macOS's own serial driver claims those
  modes first, so run mtkclient natively on the Mac for BROM work
  (`git clone https://github.com/bkerler/mtkclient && pip install ./mtkclient`).
- **Linux hosts** need no USB/IP: use `docker-compose.linux-usb.yml` instead of
  `docker-compose.mac-usb.yml`.

## Checking a phone before you buy it

`android-authenticity-check` is a read-only, root-free aid for spotting
counterfeit, rebadged or uncertified phones **at the point of sale**. It touches
nothing on the device and sends nothing off it.

On the candidate phone, enable Developer options (Settings > About > tap "Build
number" 7x) and USB debugging, plug it in, then:

```bash
android-authenticity-check            # the one attached device
android-authenticity-check -s <serial>
android-authenticity-check --json     # machine-readable
```

It reports, then gives a verdict (`LIKELY GENUINE` / `SUSPICIOUS — VERIFY` /
`LIKELY COUNTERFEIT/CLONE`):

- **Firmware integrity** — `user` build, `release-keys`, `ro.secure=1`,
  consistent fingerprints across partitions.
- **Board identity** — flags generic white-box/reference boards (`alps`, `mtk`,
  unknown manufacturers) that clones and rebadged devices often ship on.
- **Google certification** — Play Services presence; notes that having GMS is
  not the same as passing Play Integrity (install a "Play Integrity API
  Checker" app for that).
- **Real hardware** — SoC, cores, RAM, storage, screen, cameras and sensors
  (gyro/compass/fingerprint), to compare against the seller's listed specs.
- **Identity documents** — IMEI (null/placeholder or Luhn-invalid = red flag)
  and serial.
- **Freshness** — a "brand new flagship" with a multi-year-old security patch.
- **Spoofing apps** — known spec/identity-faking packages.

It is a heuristic aid for a human decision, not a guarantee.

## Rooting a phone

```bash
android-full-backup -s <serial>          # raw partitions, /data (with root), APKs, info -> /workspace/backups
fastboot flashing unlock                 # unlock the bootloader (erases the phone; some vendors need mtk/edl or an unlock code)
payload-dumper-go -p boot,init_boot payload.bin    # stock boot images from an OTA, if you don't have them
adb install $MAGISK_APK                  # Magisk app on the phone
adb push boot.img /sdcard/Download/      # patch it in Magisk: Install > Select and Patch a File
adb pull /sdcard/Download/magisk_patched-*.img
fastboot flash boot magisk_patched-*.img # init_boot instead of boot on devices launched with Android 13+
```

`magiskboot` does the same patching locally when you prefer not to install the
app on the phone: `magiskboot unpack boot.img`, edit, `magiskboot repack boot.img`.

Vendor-specific paths: `mtk` (MediaTek, needs BROM, see above), `edl`
(Qualcomm EDL mode), `heimdall` (Samsung download mode). `avbtool` and
`unpack_bootimg` / `repack_bootimg` cover vbmeta and manual boot-image edits.

## Building an app

```bash
cd /workspace/MyApp && ./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

`gradle init` or `gradle wrapper` set up new projects. The Gradle cache is a
named volume, so dependencies survive container rebuilds.

Older projects usually need the older pair: **`gradle7`** runs Gradle 7.5.1 on
JDK 11 automatically, next to the default `gradle` (8.14.6 on JDK 17). For a
one-off command on JDK 11, `JAVA_HOME=/opt/java-11 <command>`; Maven is `mvn`.
API 33 is installed alongside 36, and `$ANDROID_HOME/tools/bin/sdkmanager`
works for scripts written against the old SDK layout. `/app` is a symlink to
`/workspace` for Dockerfiles and docs that assume it.

## Settings (`.env`)

| Variable | Default | Effect |
|---|---|---|
| `OPENCODE_MODEL` | `ollama/qwen3:14b` | default model for all three tools; must support tool calling |
| `OPENCODE_FALLBACK_MODEL` | `ollama/deepseek-r1:14b-qwen-distill-q8_0` | OpenCode's `@reasoner` subagent |
| `VSCODE_PLAN_MODEL` | (the default model) | Cline Plan mode and the Roo modes in `ROO_REASONER_MODES` |
| `OLLAMA_URL` | `http://host.docker.internal:11436` | where the tools find Ollama |
| `OLLAMA_NUM_CTX` | `32768` | context window requested |
| `ROO_REASONER_MODES` | (empty) | Roo modes that use `VSCODE_PLAN_MODEL` instead of the default |
| `INSTALL_NDK` | `0` | `1` adds NDK + CMake (rebuild with `make build`) |
| `UID` / `GID` | `1000` | container user ids |

## Layout

```
Dockerfile                      the image
docker-compose.yml              dev service
docker-compose.mac-usb.yml      USB/IP sidecar + USB access (macOS)
docker-compose.linux-usb.yml    direct USB passthrough (Linux)
.devcontainer/                  VS Code Dev Container (Cline, Roo Code)
macos/host-services.sh          starts the USB/IP server and Ollama on the Mac
macos/usbip-share/              USB/IP server for the Mac (Rust, libusb)
docker/usbip-client/            sidecar that imports phones into the Docker VM
docker/bin/ai-setup             OpenCode config + model download
docker/bin/vscode-ai-config     Cline + Roo Code config
docker/bin/android-full-backup  phone backup before rooting
docker/bin/android-doctor       tools, models, phones
docker/bin/android-authenticity-check  spot counterfeit/rebadged phones before buying
docker/bin/wa-sticker-pack      build a WhatsApp sticker-pack APK so stickers survive a re-flash
bin/ai-backend            capability-aware local-vs-pod model selection + pod cost control
workspace/                      your projects and backups (mounted at /workspace)
```
