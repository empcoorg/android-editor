# Windows setup — the gentle, step-by-step version

This guide assumes **no programming experience**. It walks you, click by click,
from a plain Windows PC to a working setup, and explains every unfamiliar word the
first time it appears. If a step already makes sense to you, skip ahead — the
shorter, technical version is in
**[quickstart-linux-windows.md](quickstart-linux-windows.md#windows-docker-desktop--wsl2)**.

**What you're setting up:** a self-contained workspace on your PC that can build
Android apps and work on Android phones, driven by an **AI coding assistant** you
talk to in plain English. The AI's "brain" can be **Claude** or another online
service (easiest — just an account and a key) or a model on your own PC (only if
it's a powerful machine).

**Roughly how long:** 30–60 minutes the first time, most of it waiting for
downloads.

---

## Words you'll see (quick glossary)

- **Terminal** — a window where you type commands instead of clicking. On Windows
  it's **PowerShell** or **Command Prompt**. Open one with Start → type
  "PowerShell" → Enter.
- **Docker** — a free tool that runs a small, self-contained Linux computer
  *inside* your Windows PC, so all the Android tools behave the same on every
  machine. You don't have to understand it; you just install it and leave it
  running.
- **Repository ("repo")** — this project's folder of files, hosted on GitHub.
- **Clone** — download a copy of the repo to your PC (and be able to get updates).
- **`.env` file** — a plain text settings file where you paste your choices and
  your secret keys. (The dot at the front is part of the name.)
- **API key** — a long secret password that lets this project use an online AI
  service on your account. Treat it like a password; never share it.

---

## Step 1 — Install the three free programs

Install these in order. Accept the default options in every installer unless told
otherwise.

1. **Docker Desktop** — the engine that runs everything.
   - Go to **https://www.docker.com/products/docker-desktop/** and download for
     Windows.
   - Run the installer, keep **"Use WSL 2 instead of Hyper-V"** ticked (it's the
     default), finish, and **restart your PC** if it asks.
   - After restarting, **open Docker Desktop** and leave it running (a whale icon
     sits near the clock). The first launch may take a minute and ask you to
     accept terms — that's fine. You do **not** need to create a Docker account.

2. **Git** — downloads this project and its updates.
   - Easiest (no typing): install **GitHub Desktop** from
     **https://desktop.github.com/** — it gives you buttons instead of commands.
   - Or the classic tool: **Git for Windows** from **https://git-scm.com/download/win**
     (accept all defaults).

3. **Visual Studio Code ("VS Code")** — where you'll actually work.
   - Get it from **https://code.visualstudio.com/**.
   - After installing, open it, click the **Extensions** icon on the left
     (four squares), search **"Dev Containers"** (by Microsoft), and click
     **Install**. This is what lets VS Code use the Docker workspace.

## Step 2 — Download (clone) this project

**With GitHub Desktop (recommended):**
1. Open GitHub Desktop → **File → Clone repository → URL** tab.
2. Paste: `https://github.com/empcoorg/android-editor`
3. Pick a folder you'll remember (e.g. `Documents`), click **Clone**.
4. When it's done, **Repository → Show in Explorer** opens the folder.

**Or in a terminal:** open PowerShell, then:
```powershell
cd $HOME\Documents
git clone https://github.com/empcoorg/android-editor
cd android-editor
```

## Step 3 — Make your settings file (`.env`)

The project ships a template called `.env.example`. You make your own copy called
`.env` and edit it.

- **In File Explorer:** open the `android-editor` folder, copy `.env.example`,
  paste it in the same folder, and rename the copy to exactly **`.env`** (delete
  the `.example` part). If Windows hides file extensions and won't let you, do it
  in a terminal instead:
```powershell
cd $HOME\Documents\android-editor
copy .env.example .env
notepad .env       # opens it so you can edit (save with Ctrl-S)
```
You'll edit `.env` in the next step. It's just text — lines like `NAME=value`.
Lines starting with `#` are notes and are ignored.

## Step 4 — Give the AI a brain (pick ONE)

A capable AI model needs a lot of memory. Unless your PC has **24 GB+ of RAM and a
strong GPU**, don't run it locally — use an online service. In rough order of
easiest-first:

### Option A (easiest): Claude
1. Go to **https://console.anthropic.com/**, sign up (email or Google).
2. Open **Billing** and add a small amount of credit (e.g. $5–$10).
3. Open **API Keys → Create Key**, give it any name, and **copy** the key (it
   starts with `sk-ant-`). You won't be able to see it again, so paste it
   somewhere safe for a moment.
4. In your `.env`, set these three lines (replace the placeholder):
   ```
   LLM_BACKEND=anthropic
   ANTHROPIC_API_KEY=sk-ant-paste-your-key-here
   ANTHROPIC_MODEL=claude-sonnet-5-5
   ```
   Save the file. Done — skip to Step 5.

### Option B (easy): a hosted open-source AI (Together AI or DeepInfra)
Runs open-source models for you; also just an account + a key.
1. Sign up at **https://www.together.ai/** (or **https://deepinfra.com/**).
2. Create an **API key** in their dashboard and copy it.
3. In `.env`:
   ```
   LLM_BACKEND=cloud
   CLOUD_LLM_URL=https://api.together.xyz/v1
   CLOUD_LLM_MODEL=Qwen/Qwen3-235B-A22B-fp8-tput
   CLOUD_LLM_KEY=paste-your-key-here
   ```
   (DeepInfra instead: `CLOUD_LLM_URL=https://api.deepinfra.com/v1/openai`,
   `CLOUD_LLM_MODEL=Qwen/Qwen3-32B`.) Save and skip to Step 5.

### Option C: RunPod — rent a GPU by the hour (cheapest for heavy use, most steps)
RunPod lets you rent a graphics card in the cloud. Making the account is easy;
*starting a model server on it* is the advanced part, so only pick this if the
others don't suit you.
1. Go to **https://www.runpod.io/**, click **Sign Up**, register (email or
   Google), and verify your email.
2. Open **Billing** (left menu) and add credit — $10 is plenty to start. RunPod is
   pay-as-you-go; **you're only charged while a GPU is actually running.**
3. Open **Settings → API Keys → + Create API Key**, name it, and **copy** the key
   (keep it safe — treat it like a password).
4. Starting a model server (a "pod" running **vLLM**) and getting its web address
   is beyond a few clicks — follow the repo's worked example in the README,
   **[Cloud model backend → Worked example: RunPod](../README.md#worked-example-runpod-for-burst-gpu)**.
   You'll end up pasting four things into `.env`: the pod's URL, its model name,
   its key, and your RunPod API key:
   ```
   LLM_BACKEND=cloud
   CLOUD_PROVIDER=runpod
   CLOUD_LLM_URL=https://<your-pod-id>-8000.proxy.runpod.net/v1
   CLOUD_LLM_MODEL=qwen3-32b
   CLOUD_LLM_KEY=the-pods-key
   RUNPOD_API_KEY=your-runpod-api-key
   ```
   **Remember to stop the pod when you're done** (`make cloud-down`, or the
   **Stop** button in RunPod) or it keeps charging.

> Not sure which to choose? Start with **Option A (Claude)**. You can change
> `.env` and switch anytime.

## Step 5 — Start the workspace

Make sure **Docker Desktop is running** (whale icon near the clock). Then open a
terminal in the project folder — in File Explorer, click the address bar, type
`powershell`, and press Enter — and run:

```powershell
docker compose build dev     # builds the workspace — first time ~20 min, lots of downloading
docker compose up -d dev     # starts it running in the background
```
If you only use an online AI (Options A–C), you can ignore anything about
"Ollama"; that's only for running a model on your own PC.

## Step 6 — Open it and start working

1. In VS Code: **File → Open Folder →** pick the `android-editor` folder.
2. Press **F1**, type **"Reopen in Container"**, and pick
   *Dev Containers: Reopen in Container*. The first time it sets things up (a few
   minutes). When it reopens, VS Code is now working *inside* the Docker
   workspace, with the **Cline** and **Roo Code** AI assistants installed.
3. Click the Cline or Roo Code icon in the left bar and type what you want in
   plain English.

## Connecting a phone (the easy way: Wi-Fi)

You don't need any cables. On the **phone**:
1. Settings → About phone → tap **Build number** 7 times to unlock *Developer
   options*.
2. Settings → System → **Developer options** → turn on **Wireless debugging** →
   tap it → note the **IP address and port** shown.
3. Put the phone and PC on the **same Wi-Fi network**.

Then in the VS Code terminal (inside the container):
```bash
adb pair <ip>:<pairing-port>     # type the 6-digit code the phone shows
adb connect <ip>:<port>
adb devices                      # your phone should be listed
```

## When something goes wrong

- **"docker: command not found" / nothing happens** — Docker Desktop isn't
  running. Open it, wait for the whale icon to stop animating, try again.
- **The build seems stuck** — the first build downloads several gigabytes; give it
  time. A progress bar that pauses is normal.
- **The AI "replies" but never does anything** — your model can't use tools. On
  Options A–C this shouldn't happen; if you switched to a local model, see
  **[the tool-calling rule](../README.md#the-tool-calling-rule)**.
- **A key stopped working / you're worried it leaked** — delete it in the
  service's dashboard and create a new one; paste the new one into `.env`.
- Keep your `.env` private. It's already set to never be uploaded to GitHub.
