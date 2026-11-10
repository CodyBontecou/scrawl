# Recordings to a herdr agent

Read this before changing `src/herdr.rs`. It replaces OpenCode as the place recordings go (ScrawlKit uploads from the phone and `scrawl send`; Mac captures of a dev site are still routed to OpenCode sessions) with a coding agent running in [herdr](https://herdr.dev), driven through the `herdr` CLI.

## Turning it on

In `~/.config/scrawl/config.json`:

```json
{
  "agentBackend": "herdr",
  "herdr": {
    "kind": "claude",
    "mode": "fresh",
    "agentArgs": [],
    "defaultFolder": "~/dev/scratch",
    "focus": false
  },
  "routing": {
    "apps": { "bontecou.Voxboard": "~/dev/vox.md" }
  }
}
```

- `kind`: any kind `herdr agent` lists (`claude`, `codex`, `pi`, `opencode`, …). Default `claude`.
- `mode`: `"fresh"` (default) starts a new agent per recording; `"reuse"` prompts an idle or done agent of that kind already working in the folder and starts one only when there is none.
- `agentArgs`: passed to the agent after `--` (`["--permission-mode", "acceptEdits"]`). Without one, a fresh agent stops at its first approval and herdr shows it as `blocked`.
- `defaultFolder`: where a recording goes when no `routing.apps` key matches its app. Without it, an unmatched recording is saved on the Mac and not sent.
- `focus`: switch herdr's UI to the agent once it's prompted. Off by default so a recording never steals your screen.
- `bin`: the herdr binary. Default `~/.local/bin/herdr`, then Homebrew, then `PATH` (Scrawl.app doesn't see the login shell's PATH).

`routing.apps` keys match the recording's bundle ID (or app name for old timelines) by longest case-insensitive prefix.

## What a send does

`resolve` turns the app into the session string `herdr:<folder>` and the title `herdr · <folder name>`, which the phone shows as "Sent to herdr · vox.md". `forward`:

1. finds the herdr workspace with a pane in the folder (exactly, then inside it) and opens a `scrawl` tab there with `--no-focus`, or creates a workspace labelled with the folder's name;
2. `herdr agent start scrawl-<bundle id tail> --kind <kind> --pane <root pane> [-- agentArgs]`, which returns once the agent is ready for input;
3. `herdr agent prompt <agent> <text>`: the bundle's `summary.md`, the contact sheet's and `scrawl.json`'s paths, and the `scrawl zoom` hint.

Two things found against a live herdr 0.8.0 that the code waits out:

- A new tab's shell isn't at its prompt for a moment, and `agent start` answers `agent_pane_busy` until it is. The start is retried on that error for `pane_ready` (10 s).
- herdr reports a just-started Claude `idle` at once and `blocked` about a second later when it asks whether to trust a folder it hasn't seen. Typing the prompt then answers the question with its default, "No, exit", and the recording is lost. So `forward` waits `settle` (1.5 s) after a start and refuses to prompt a `blocked` agent; the delivery retry prompts it once the question is answered in herdr.

Each step is recorded in the bundle's `.herdr.json` (`pane_id`, `agent`, `prompted`). `delivery`'s retries (`.deliver.json`) call `forward` again; it reuses the agent or pane from the marker and never prompts a bundle twice. The `herdr:` prefix chooses the forward (`forward_any`), so a pending retry still goes to herdr after the backend is switched back.

## Tests

`herdr::tests` drive `forward_with` through a fake `Herdr` that records commands and scripts answers. Never point tests at a real herdr server.
