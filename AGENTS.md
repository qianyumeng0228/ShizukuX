# Agent Guidelines (Claude / Gemini / Cursor / Aider / et al.)

This file is the canonical entry point for AI coding agents. It points to
the per-agent guides that already exist in this repo and lists the rules
that apply to every agent regardless of vendor.

## Per-agent guides

| Agent | File |
|-------|------|
| Claude Code | [`CLAUDE.md`](CLAUDE.md) |
| Gemini | [`GEMINI.md`](GEMINI.md) |
| Jules | [`JULES.md`](JULES.md) |

Read the guide for the agent you are. They share most rules but the
Claude file is the most fleshed-out — start there if your guide is sparse.

## Rules that apply to every agent

1. **Never reintroduce the items in `CLAUDE.md` → "Critical Crash Rules"**.
   Those each correspond to a real production crash.
2. **The build target is `:manager:assembleRelease`** for verification.
   Debug builds skip Sentry symbol upload and are fine for fast iteration.
3. **Do not edit `key.jks`, `signing.properties`, or files matching
   `secrets*`** — they are signing material.
4. **CI is GitHub Actions**, single workflow at `.github/workflows/app.yml`.
   Inspect it before assuming how a build works.
5. **Use `scripts/dev/*`** for common commands instead of re-deriving from
   `build.gradle` each session.

## Project quick-ref

- **Entry activity:** `MainActivity` (NOT `HomeActivity` — that's abstract)
- **Settings keys:** `manager/src/main/java/af/shizuku/manager/ShizukuSettings.java` inner class `Keys`
- **Preference XML:** `manager/src/main/res/xml/settings_*.xml`
- **Theme:** `Theme.Material3Expressive.*` — use M3 components, not AppCompat
- **App widgets:** `RemoteViews` only allows framework views — do NOT use
  `MaterialButton` / `MaterialSwitch` etc. inside `widget_*.xml`
