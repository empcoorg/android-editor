# AI backends, cloud quickstarts, and when Docker is actually used

This repo has two separable halves:

1. **An Android build + root toolkit** (adb, fastboot, Magisk, mtkclient, super-image
   tools, …) that runs in a Docker dev container.
2. **An AI coding agent** (Cline, Roo Code, OpenCode) that drives that toolkit, and
   which can talk to a **local**, **cloud**, or **Claude** model.

Most people do not have a workstation that runs a capable open-source model well, so
this guide leads with the cloud and Claude paths. It also answers a common question:
*when is Docker involved and when is it not?*

---

## What runs where (is Docker even used?)

| Runs on the **host** (no Docker) | Runs **in the container** | Runs in the **cloud** |
|---|---|---|
| Ollama — the local model server (Metal/GPU accelerated) | The AI agents: Cline, Roo Code, OpenCode | The big model on a rented GPU, or a hosted API |
| The USB/IP server, `bin/ai-backend`, `bin/pick-model` | The Android **build + root** toolchain (magiskboot, mtkclient, payload-dumper, lp/avb tools…) | Claude, via `LLM_BACKEND=anthropic` |
| MediaTek **BROM/preloader** flashing (macOS claims the serial port first) | adb/fastboot/root steps **when driven by the agent** | |

**Key point:** plain device administration over adb — pushing files, editing an app's
database, reading logs, setting wallpapers, installing APKs — is just `adb` over USB or
Wi-Fi **from the host**. It does **not** need the container at all. The dev container only
matters when you need the containerised **build/root toolchain** or you're running the
**AI agents**. If you've been doing phone admin over Wi-Fi with the container stopped,
that's expected — Docker was never in the loop.

### Is Docker the right tool here?

Keep it, but know its job:

- **Worth containerising:** the Android build/root toolchain (dozens of finicky binaries,
  plus x86 emulation because Google ships Android build-tools x86-only) and the agent
  sandbox. That reproducibility is exactly what containers are good at.
- **Doesn't need Docker:** everyday device admin over adb/Wi-Fi (just use host `adb`), and
  running the agent against a **cloud or Claude** model — Cline/Roo can run in plain local
  VS Code pointed at the endpoint, no container required.

So: the container is for *building/flashing* and for *sandboxing a local-model agent*. If
you only want an agent backed by the cloud or Claude, you can skip it.

---

## Choosing a model backend

One switch in `.env`: `LLM_BACKEND=local | cloud | anthropic`. Every AI tool (Cline, Roo
Code, OpenCode) follows it.

| Backend | What it is | Need a GPU? | Cost |
|---|---|---|---|
| `local` | Ollama on your machine | Yes — 24 GB+ unified/VRAM for a *usable* model | Free |
| `cloud` | Any OpenAI-compatible endpoint: a hosted OSS API, or a GPU you rent | No (hosted API) / Yes (self-host) | Per token, or per GPU-hour |
| `anthropic` | Claude | No | Per token |

### Model size matters — small models go in rabbit holes

The agents work by **calling tools** (running commands, editing files). Models below
**~20B params** often fail to call tools reliably and loop or rabbit-hole instead of making
progress. The practical floor for local agent work is the **workstation tier** (a ~27–30B
model, ~18 GB, on a 24 GB+ machine). The catalog (`bin/pick-model list`):

- **compact (~16 GB):** `qwen3.5:9b`, `gpt-oss:20b` — *fit, but unreliable for agents.*
- **workstation (~24 GB):** `qwen3.8:27b` (default), `muse-glimmer:30b` (default @reasoner),
  `qwen3-coder:30b` — *the reliable floor.*
- **big (64 GB+):** `qwen3.5:122b` — *best local, needs a lot of RAM.*

`bin/pick-model check <model>` runs a live tool-calling smoke test; `bin/pick-model set
default=` refuses a model that fails it. **If you can't run 24 GB+ locally, don't fight a
too-small model — use a cloud or Claude backend below.**

### Escalation policy (`bin/ai-backend`)

Run locally while the local model fits the task. Escalate to the cloud/Claude model when
(1) a task needs a bigger model than local runs well, or (2) local is **stalling** —
looping or retrying without progress. Escalating early on a stall is deliberate: a quick
jump to bigger compute usually breaks the loop faster, and in less wall-clock time, than
letting a small model grind.

```
bin/ai-backend status        # current backend, models, instance state, idle timer
bin/ai-backend escalate      # jump to the bigger backend now (Claude if set, else cloud)
bin/ai-backend stall         # signal a stalled local attempt; auto-escalates at ESCALATE_AFTER
bin/ai-backend stop          # back to local; stop the cloud instance (billing pauses)
```

Knobs (in `.env`): `LOCAL_MAX_B` (largest size local runs well), `ESCALATE_AFTER` (stalled
attempts before auto-escalating; default **2** — eager, to bail out of rabbit holes),
`IDLE_MINUTES` (auto-stop the rented instance when idle). `AI_BACKEND_DRYRUN=1` previews a
decision without changing anything.

---

## Quickstart: local (macOS)

```
bash macos/host-services.sh          # start Ollama + USB/IP on the host
make build && make up                # build + start the dev container
make models                          # pull the default + reasoner models into host Ollama
```
Open the repo in VS Code → *Reopen in Container* → Cline / Roo Code are configured
automatically. `.env` default: `OPENCODE_MODEL=ollama/qwen3.8:27b`.

## Quickstart: cloud — hosted OSS API (no GPU to manage)

The easiest cloud path. A hosted, OpenAI-compatible endpoint serves the model; you pay per
token and there's nothing to start or stop. In `.env`:

```
LLM_BACKEND=cloud
# Together AI
CLOUD_LLM_URL=https://api.together.xyz/v1
CLOUD_LLM_MODEL=Qwen/Qwen3-235B-A22B-fp8-tput
CLOUD_LLM_KEY=<your Together key>
# — or DeepInfra:  https://api.deepinfra.com/v1/openai   model Qwen/Qwen3-32B
# — or Fireworks:  https://api.fireworks.ai/inference/v1 model accounts/fireworks/models/qwen3-30b-a3b
```
Leave `CLOUD_*_CMD` empty (nothing to start/stop). `make cloud-status` shows the backend;
recreate the container (`make up`) so the agents pick it up. These providers host large
OSS models that comfortably call tools — a good match when local can't.

## Quickstart: cloud — your own GPU (RunPod, Vast.ai, Lambda)

Rent a GPU, run vLLM serving an OSS model with an OpenAI-compatible API on `:8000`, and
point `CLOUD_LLM_URL` at it. `bin/ai-backend` can start the instance on demand and stop it
after `IDLE_MINUTES` idle so you don't pay for an idle GPU.

**RunPod** (worked example; a 96 GB GPU runs Qwen3-32B at bf16, billed per GPU-hour):
```
LLM_BACKEND=cloud
CLOUD_PROVIDER=runpod
CLOUD_LLM_URL=https://<podid>-8000.proxy.runpod.net/v1
CLOUD_LLM_MODEL=qwen3-32b
CLOUD_INSTANCE_ID=<podid>
CLOUD_START_CMD='runpodctl pod start $CLOUD_INSTANCE_ID'
CLOUD_STOP_CMD='runpodctl pod stop $CLOUD_INSTANCE_ID'
CLOUD_STATUS_CMD='runpodctl pod get $CLOUD_INSTANCE_ID'
RUNPOD_API_KEY=<key>      # for runpodctl; keep it out of git
```
Then `make cloud-up` (switch + start the pod), `make cloud-down` (stop it; the model stays
cached on the pod's network volume, which costs a few $/month until deleted).

**Vast.ai / Lambda** work the same way — rent a GPU, serve vLLM, set `CLOUD_LLM_URL` to its
endpoint, and (optionally) fill in that provider's start/stop/status CLI in the
`CLOUD_*_CMD` variables. Any provider that gives an OpenAI-compatible URL plugs in
unchanged.

> Self-hosting a GPU is more setup and you pay by the hour. If you just want it to work,
> use a **hosted OSS API** or **Claude** instead.

## Quickstart: Claude (frontier, no GPU)

Point the agents at Claude — it always calls tools, so no rabbit holes, and there's nothing
to run or rent. In `.env`:

```
LLM_BACKEND=anthropic
ANTHROPIC_API_KEY=sk-ant-...
ANTHROPIC_MODEL=claude-sonnet-5-5
```
Recreate the container (`make up`) and Cline, Roo Code, and OpenCode are all configured for
Claude. Billed per token by Anthropic. Keep the key out of git (it lives in `.env`, which is
git-ignored).

---

## Security

`.env` is git-ignored and holds your keys (`ANTHROPIC_API_KEY`, `CLOUD_LLM_KEY`,
`RUNPOD_API_KEY`, …) in plaintext on disk. It is never committed, but treat it like any
secret file: don't paste it, and rotate a key if it's ever exposed.
