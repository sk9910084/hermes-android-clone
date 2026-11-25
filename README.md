# Hermes Clone (Android)

> **Unofficial.** This project is not affiliated with or endorsed by Nous Research.
> It is a personal Android reimplementation of the [Hermes Agent](https://github.com/NousResearch/hermes-agent)
> concept, written from scratch in Kotlin + Jetpack Compose.
> The bundled SKILL.md files (see below) are copyright Nous Research, MIT licence.

---

## What it does

Hermes Clone is a native Android AI-agent app that talks directly to any
OpenAI-compatible LLM endpoint (OpenRouter, Gemini, your own server, etc.):

| Feature | Status |
|---------|--------|
| Streaming chat with tool-call loop (max 25 iterations) | ✅ v1 |
| Session list — create / rename / delete / resume | ✅ v1 |
| Skills — bundled SKILL.md files, enable/disable per skill | ✅ v1 |
| Memories — agent saves facts; view / edit / delete by hand | ✅ v1 |
| To-do list — agent can add / update todos; manage by hand | ✅ v1 |
| Settings — provider, model, API key (encrypted on device), max iterations, streaming | ✅ v1 |
| No backend, no account, no server to host | ✅ v1 |
| No baked-in API key | ✅ v1 |
| No telemetry, no ads, no in-app purchases | ✅ v1 |

### What is NOT in v1

| Feature | Reason |
|---------|--------|
| Telegram / Discord / WhatsApp gateway | Needs a 24/7 server and bot tokens; a phone cannot host it reliably |
| Cron / scheduled tasks (background) | Android kills background processes; a UI-only stub may come in v2 |
| Browser automation, terminal, code execution | Excluded for safety |
| Image generation, TTS, vision input | Out of scope for v1 |
| Kanban board UI, curator skill auto-archiving | Out of scope for v1 |
| Plugin marketplace | Out of scope for v1 |

---

## How to build

### Prerequisites
- Android Studio Hedgehog or later (or IntelliJ with Android plugin)
- JDK 17
- Android SDK with API 34 installed

> **Important:** The Gradle wrapper jar is not committed.
> Run `gradle wrapper --gradle-version 8.7` once locally (or let CI do it).

### Local build
```bash
# 1. Generate the wrapper (one-time)
gradle wrapper --gradle-version 8.7 --distribution-type bin
chmod +x gradlew

# 2. Copy bundled skills (see below)

# 3. Build
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### CI build (recommended)
Push to `main`. The GitHub Actions workflow (`.github/workflows/build-apk.yml`)
installs Gradle 8.7, generates the wrapper, builds the debug APK, and uploads it
as the `hermes-clone-debug-apk` artifact (retained 30 days). No signing in v1
(debug keystore only).

---

## How to fill in the API key

1. Open the app → tap **⋮ More** → **Settings**.
2. Select a provider (OpenRouter is the default).
3. Paste your API key into the **API key** field. It is stored only in
   Android's `EncryptedSharedPreferences` on this device — it is never logged,
   never sent anywhere except to the LLM provider you configured, and never
   included in any backup that leaves the device.
4. Optionally tap **Fetch** next to the model field to load the provider's
   current model list.
5. Tap **Test connection** to verify the key works.

### Free models
- **OpenRouter** (default): models whose id ends in `:free` are free-tier.
  Example: `meta-llama/llama-3.3-70b-instruct:free`.
- **Gemini**: use a key from [Google AI Studio](https://aistudio.google.com/).
  `gemini-2.0-flash` has a free tier.
- **Custom**: any OpenAI-compatible HTTPS endpoint (e.g. a server you run).
  The key field can be left empty when the endpoint needs no auth.

---

## Bundled skills

Skills are plain-text `SKILL.md` files shipped in `app/src/main/assets/skills/`.
They are copyright Nous Research and contributors, MIT licence.

**The asset directory is empty in this repository.** Before building, copy the
skill folders from the upstream Hermes repo
(`https://github.com/NousResearch/hermes-agent`, commit `2fcdbfea`).
See `app/src/main/assets/skills/README.md` for the exact list and copy rules.

Recommended set (phone-sensible, no platform-gated skills):

| Skill folder | Category |
|---|---|
| `note-taking/<skill>/` | note-taking |
| `research/<skill>/` | research |
| `productivity/<skill>/` | productivity |
| `software-development/codebase-inspection/` | software-development |
| `software-development/simplify-code/` *(if present)* | software-development |
| `web/<skill>/` | web |
| `creative/<skill>/` | creative |
| `autonomous-ai-agents/hermes-agent/` | autonomous-ai-agents |

Once copied, the Skills screen lists them automatically. Enable a skill in
the app to inject its instructions into the next turn.

---

## Architecture notes

- **Agent loop** — reimplemented in Kotlin from the Hermes spec (`PLAN.md §2.1`).
  One class per turn phase (`AgentLoop`, `ContextBuilder`, `Compression`, `Subagent`).
  Strict OpenAI role alternation; system prompt frozen at session creation; max 25
  iterations (configurable up to 100 in Settings).
- **Storage** — Room (SQLite) for sessions, messages (with FTS4 for session search),
  memories, and todos. API key in `EncryptedSharedPreferences`.
- **Networking** — OkHttp + kotlinx-serialization; SSE streaming for chat completions.
  Custom provider requires HTTPS (cleartext blocked on targetSdk 34).
- **No backend** — the app calls the LLM provider directly from the phone.
- **Offline behaviour** — all screens work without network. The send button shows a
  clear error when the device is offline; the draft is preserved.

---

## Credits

- [Nous Research / hermes-agent](https://github.com/NousResearch/hermes-agent) — original Hermes Agent, MIT licence.
  Bundled SKILL.md files are from this repository, headers and authorship intact.
- This Android clone was written by Claude AI (Anthropic) from the build brief in `PLAN.md`.

---

## Licence

This Android clone code is released under the MIT licence (see `LICENSE`).
Bundled SKILL.md files carry their original Nous Research MIT licence headers.
