# Quickstart: Linux and Windows

**macOS is the default** — see **[Quick start (macOS)](../README.md#quick-start-macos)**
in the README; the `make` targets and `macos/host-services.sh` are tuned for it.
This guide covers the two differences on Linux and Windows:

1. **Ollama** runs on the host, and the container reaches it at
   `host.docker.internal:11436` — so Ollama must **listen on `0.0.0.0:11436`**, not
   its default `127.0.0.1:11434`.
2. **USB.** macOS bridges phones in over USB/IP (`host-services.sh` +
   `docker-compose.mac-usb.yml`). Linux passes the USB bus straight through
   (`docker-compose.linux-usb.yml`); Windows has no bus passthrough, so use
   **adb over Wi-Fi** (or `usbipd-win`).

The AI backends (local / cloud / Claude) are identical on every OS — see
**[ai-backends.md](ai-backends.md)**. Everything below assumes you've cloned the
repo and run `cp .env.example .env`.

> The `make dev-up` / `host-services` targets are macOS-only (they start USB/IP +
> Ollama and use the mac-usb compose file). On Linux/Windows start the container
> with `docker compose` directly as shown; `make attach`, `make models`,
> `make doctor` then work against the running container on any OS.

---

## Linux (Docker Engine)

```bash
# 1. Docker Engine + the compose plugin (Debian/Ubuntu example)
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"            # log out/in so `docker` works without sudo

# 2. Ollama on the host, listening where the container can reach it
curl -fsSL https://ollama.com/install.sh | sh
OLLAMA_HOST=0.0.0.0:11436 ollama serve &   # or set it permanently (see note below)

# 3. match the container user to yours so files aren't root-owned
printf 'UID=%s\nGID=%s\n' "$(id -u)" "$(id -g)" >> .env

# 4. build + start (phones over direct USB passthrough)
docker compose build dev                   # ~20 min once
docker compose -f docker-compose.yml -f docker-compose.linux-usb.yml up -d dev
make models                                # pull the two models into host Ollama
make attach                                # shell in the container
```

- **Make Ollama's bind permanent:** `sudo systemctl edit ollama` and add
  `Environment="OLLAMA_HOST=0.0.0.0:11436"`, then `sudo systemctl restart ollama`
  (if you installed it as a service). Otherwise keep the `OLLAMA_HOST=0.0.0.0:11436
  ollama serve` form. (Prefer a different port? Set `OLLAMA_URL` in `.env` to match.)
- **Phones over USB** work directly through `docker-compose.linux-usb.yml`
  (privileged + `/dev/bus/usb` + `/run/udev`); add a udev rule for your device's
  vendor id if adb shows it as `no permissions`. No USB/IP server, no
  `host-services.sh`.
- **Or skip USB entirely** — adb over Wi-Fi works the same everywhere:
  `adb connect <phone-ip>:5555` from inside the container.
- **Firewall:** allow the Docker bridge to reach host port 11436 if a firewall
  blocks it (e.g. `sudo ufw allow from 172.16.0.0/12 to any port 11436`).
- **arm64 hosts** (e.g. a Raspberry Pi, Ampere): the image is `linux/amd64`
  (Android build-tools are x86-only), so it runs under QEMU — slow but works;
  `docker run --privileged tonistiigi/binfmt --install amd64` registers the
  emulator if builds fail with `exec format error`.

## Windows (Docker Desktop + WSL2)

```powershell
# 1. Docker Desktop with the WSL2 backend (installer default). Start it once.
# 2. Ollama for Windows (https://ollama.com/download). Make it listen for the
#    container: set a system environment variable OLLAMA_HOST = 0.0.0.0:11436
#    (search "Edit the system environment variables"), then quit + reopen Ollama.
```
```bash
# 3. In a WSL2 shell (Ubuntu), where git + make live. Clone inside the Linux
#    filesystem (~/...), NOT /mnt/c, or the bind mount will be slow.
cp .env.example .env
docker compose build dev                   # ~20 min once
docker compose up -d dev                   # base compose; no USB passthrough on Windows
make models
make attach
```

- **`host.docker.internal` already resolves** on Docker Desktop, so the container
  reaches the Windows Ollama at `host.docker.internal:11436` with no `extra_hosts`
  tricks — just make sure Ollama is bound to `0.0.0.0:11436` (step 2).
- **Phones: use adb over Wi-Fi** — the simplest path on Windows and the one this
  repo exercises most. On the phone enable wireless debugging, then inside the
  container `adb connect <phone-ip>:5555`.
- **Phones over USB (advanced):** Windows can't hand a USB device to a container
  directly. Install [`usbipd-win`](https://github.com/dorssel/usbipd-win), then
  `usbipd bind --busid <id>` and `usbipd attach --wsl --busid <id>` to pass the
  phone into WSL2; from there the Linux USB passthrough applies, so start the
  container with `-f docker-compose.linux-usb.yml` as in the Linux section.
- **VS Code:** *Dev Containers: Reopen in Container* works through Docker Desktop;
  Cline and Roo Code install into the container exactly as on macOS. (It won't run
  `host-services.sh` — that's macOS-only — so start Ollama yourself as above.)

---

## Backends on any OS

`local` (Ollama), `cloud` (any OpenAI-compatible endpoint — a hosted API or a GPU
you rent), and `anthropic` (Claude) all work the same on Linux and Windows; only
the host Ollama setup above is OS-specific. Pick one in `.env`
(`LLM_BACKEND=...`). If your machine can't run a 24 GB+ local model well, use a
cloud or Claude backend instead of fighting a too-small one — see
**[ai-backends.md](ai-backends.md)** for the quickstarts and the escalation policy.
