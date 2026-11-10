//! Delivery to a coding agent running in herdr (<https://herdr.dev>) instead of OpenCode.
//!
//! On with `"agentBackend": "herdr"` in `~/.config/scrawl/config.json`. A recording's app is
//! mapped to a project folder through `routing.apps` (bundle ID or prefix → folder), else
//! `herdr.defaultFolder`. The "session" handed through ingest and `delivery` is
//! `herdr:<folder>`, so a pending retry keeps going to herdr even if the backend is switched
//! back.
//!
//! Forwarding a bundle, in the default `"fresh"` mode:
//!
//! 1. find the herdr workspace with a pane in the folder (else create one there),
//! 2. open a new tab in it (`--no-focus`) and `herdr agent start` the configured kind there,
//! 3. `herdr agent prompt` it with the recording's summary and file pointers.
//!
//! `"reuse"` mode prompts an idle agent already working in the folder and only starts a fresh
//! one when there is none. Each step is written to `.herdr.json` in the bundle, so a retry after
//! a partial failure reuses the pane and agent it already made instead of spawning another, and
//! a bundle that was prompted is never prompted twice.
//!
//! The `herdr` CLI is driven through [`Herdr`], which tests replace; tests never touch a real
//! herdr server.

use std::path::{Path, PathBuf};
use std::process::Command;

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::capture::scrawl_log;

/// Session strings that mean "a herdr agent working in this folder".
pub const PREFIX: &str = "herdr:";
/// Progress of a bundle's forward, so retries pick up where the last try stopped.
pub const MARKER: &str = ".herdr.json";

/// `herdr` in config.json.
#[derive(Clone, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct HerdrConfig {
    /// The herdr binary (default: `~/.local/bin/herdr`, Homebrew, then `PATH`).
    #[serde(default)]
    pub bin: Option<String>,
    /// The agent kind to start (`herdr agent` lists them; default `claude`).
    #[serde(default)]
    pub kind: Option<String>,
    /// `"fresh"` (default): a new agent per recording. `"reuse"`: an idle agent in the folder.
    #[serde(default)]
    pub mode: Option<String>,
    /// Arguments passed to the agent after `--` (`["--permission-mode", "acceptEdits"]`).
    #[serde(default)]
    pub agent_args: Vec<String>,
    /// Where recordings go when no `routing.apps` entry matches their app.
    #[serde(default)]
    pub default_folder: Option<String>,
    /// Switch herdr's UI to the agent once it's prompted (default off).
    #[serde(default)]
    pub focus: Option<bool>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    Fresh,
    Reuse,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Settings {
    pub kind: String,
    pub mode: Mode,
    pub agent_args: Vec<String>,
    pub focus: bool,
    /// How long a just-started agent gets to show a first-run question (Claude's folder trust)
    /// before it's prompted: herdr reports it `idle` at once and `blocked` a moment later.
    pub settle: std::time::Duration,
    /// How long to wait for a new pane's shell to reach its prompt (`agent_pane_busy` until it
    /// does).
    pub pane_ready: std::time::Duration,
}

impl Settings {
    pub fn from_config(config: &HerdrConfig) -> Self {
        Self {
            kind: config
                .kind
                .clone()
                .filter(|k| !k.trim().is_empty())
                .unwrap_or_else(|| "claude".into()),
            mode: match config.mode.as_deref() {
                Some("reuse") => Mode::Reuse,
                _ => Mode::Fresh,
            },
            agent_args: config.agent_args.clone(),
            focus: config.focus.unwrap_or(false),
            settle: std::time::Duration::from_millis(1500),
            pane_ready: std::time::Duration::from_secs(10),
        }
    }
}

/// Runs one herdr CLI command and returns its JSON `result`.
pub trait Herdr {
    fn call(&mut self, args: &[String]) -> Result<Value>;
}

/// The real `herdr` binary.
pub struct Cli {
    bin: PathBuf,
}

impl Cli {
    pub fn locate(configured: Option<&str>) -> Result<Self> {
        let home = dirs::home_dir().unwrap_or_default();
        let expand = |path: &str| match path.strip_prefix("~/") {
            Some(rest) => home.join(rest),
            None => PathBuf::from(path),
        };
        if let Some(bin) = configured.filter(|b| !b.trim().is_empty()) {
            let path = expand(bin);
            if path.is_file() {
                return Ok(Self { bin: path });
            }
            bail!("herdr.bin {} is not a file", path.display());
        }
        // Scrawl.app is launched from Finder, so it doesn't see the login shell's PATH.
        let known = [
            home.join(".local/bin/herdr"),
            PathBuf::from("/opt/homebrew/bin/herdr"),
            PathBuf::from("/usr/local/bin/herdr"),
        ];
        known
            .into_iter()
            .find(|p| p.is_file())
            .or_else(|| crate::handoff::on_path("herdr"))
            .map(|bin| Self { bin })
            .context("Could not find herdr; set herdr.bin in config.json")
    }
}

impl Herdr for Cli {
    fn call(&mut self, args: &[String]) -> Result<Value> {
        let output = Command::new(&self.bin)
            .args(args)
            .output()
            .with_context(|| format!("Could not launch {}", self.bin.display()))?;
        let parse = |bytes: &[u8]| serde_json::from_slice::<Value>(bytes).ok();
        let response = parse(&output.stdout).or_else(|| parse(&output.stderr));
        if let Some(error) = response.as_ref().and_then(|r| r.get("error")) {
            bail!("herdr {}: {error}", args.first().map_or("", String::as_str));
        }
        if !output.status.success() {
            bail!(
                "herdr {} failed: {}",
                args.join(" ").chars().take(80).collect::<String>(),
                String::from_utf8_lossy(&output.stderr).trim()
            );
        }
        Ok(response
            .and_then(|mut r| r.get_mut("result").map(Value::take))
            .unwrap_or(Value::Null))
    }
}

/// What a bundle's forward has done so far.
#[derive(Clone, Debug, Default, Deserialize, Serialize, PartialEq, Eq)]
pub struct Marker {
    #[serde(default)]
    pub pane_id: Option<String>,
    #[serde(default)]
    pub agent: Option<String>,
    #[serde(default)]
    pub prompted: bool,
}

impl Marker {
    fn read(bundle: &Path) -> Self {
        std::fs::read(bundle.join(MARKER))
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default()
    }

    fn write(&self, bundle: &Path) -> Result<()> {
        let tmp = bundle.join(".herdr.json.tmp");
        std::fs::write(&tmp, serde_json::to_vec_pretty(self)?)?;
        std::fs::rename(&tmp, bundle.join(MARKER))
            .with_context(|| format!("Could not write {}", bundle.join(MARKER).display()))
    }
}

/// Whether config.json picks herdr as the agent backend.
pub fn enabled() -> bool {
    crate::handoff::agent_backend().as_deref() == Some("herdr")
}

/// The folder a `herdr:` session string names.
pub fn folder_of(session: &str) -> Option<PathBuf> {
    session
        .strip_prefix(PREFIX)
        .map(str::trim)
        .filter(|f| f.starts_with('/'))
        .map(PathBuf::from)
}

fn session_for(folder: &Path) -> (String, String) {
    let name = folder
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_else(|| folder.display().to_string());
    (
        format!("{PREFIX}{}", folder.display()),
        format!("herdr · {name}"),
    )
}

/// The folder `app` (a bundle ID or app name) is built from: the longest `routing.apps` key it
/// starts with (case-insensitive), else `default`.
pub fn folder_for_app(
    app: Option<&str>,
    apps: &[(String, PathBuf)],
    default: Option<PathBuf>,
) -> Option<PathBuf> {
    let app = app.map(str::to_lowercase);
    app.as_deref()
        .and_then(|app| {
            apps.iter()
                .filter(|(key, _)| !key.is_empty() && app.starts_with(&key.to_lowercase()))
                .max_by_key(|(key, _)| key.len())
                .map(|(_, folder)| folder.clone())
        })
        .or(default)
}

/// [`crate::phone`]'s `resolve` hook for herdr: `(session, title)` for a recording from `app`.
/// A recorded `herdr:` destination wins; an OpenCode `ses_…` one is ignored.
pub fn resolve(session_id: Option<&str>, app: Option<&str>) -> Result<(String, String)> {
    if let Some(folder) = session_id.and_then(folder_of) {
        return Ok(session_for(&folder));
    }
    let config = crate::handoff::herdr_config();
    let home = dirs::home_dir().unwrap_or_default();
    let default = config
        .default_folder
        .as_deref()
        .map(|f| match f.strip_prefix("~/") {
            Some(rest) => home.join(rest),
            None => PathBuf::from(f),
        });
    let folder =
        folder_for_app(app, &crate::handoff::routing_apps(), default).with_context(|| {
            format!(
                "No folder for {}; add it to routing.apps or set herdr.defaultFolder",
                app.unwrap_or("this app")
            )
        })?;
    if !folder.is_dir() {
        bail!("{} is not a folder", folder.display());
    }
    Ok(session_for(&folder))
}

/// `delivery::Forward` for herdr sessions.
pub fn forward(bundle_dir: &Path, session: &str) -> Result<()> {
    let folder = folder_of(session).context("Not a herdr session")?;
    let config = crate::handoff::herdr_config();
    let settings = Settings::from_config(&config);
    let mut cli = Cli::locate(config.bin.as_deref())?;
    let text = prompt_text(bundle_dir)?;
    forward_with(&mut cli, bundle_dir, &folder, &text, &settings)
}

/// Picks the forward for a session: herdr's for `herdr:…`, OpenCode's otherwise.
pub fn forward_any(bundle_dir: &Path, session: &str) -> Result<()> {
    if session.starts_with(PREFIX) {
        forward(bundle_dir, session)
    } else {
        crate::handoff::send_bundle_to_session(bundle_dir, session)
    }
}

/// Picks the resolver from config: herdr's when `agentBackend` is `"herdr"`.
pub fn resolve_any(session_id: Option<&str>, app: Option<&str>) -> Result<(String, String)> {
    if enabled() {
        resolve(session_id, app)
    } else {
        crate::handoff::resolve_opencode_session(session_id, app)
    }
}

/// The prompt for a bundle: its summary, then where the images and timeline are and how to zoom.
pub fn prompt_text(bundle_dir: &Path) -> Result<String> {
    let (directory, bundle) = crate::handoff::load_bundle(bundle_dir)?;
    let summary = crate::sketch::summary_markdown(&bundle, &directory);
    Ok(format!(
        "{}\n\nThis is a Scrawl recording: I talked, pointed and drew over the app to show you what I mean. Open the contact sheet {} (the capture's keyframes in order) and read {} for the timestamped transcript, marks, and interactions.\nTo look closely at a moment (an animation, a glitch, what the narration points at), run:\nscrawl zoom '{}' --at <M:SS or \"a phrase that was said\"> [--span 2] [--frames 12]\nIt saves a dense contact sheet of that moment with each frame's time and prints what was said; open the PNG before acting on the feedback. This capture is {} ms long.",
        summary.trim_end(),
        directory.join(&bundle.contact_sheet).display(),
        directory.join("scrawl.json").display(),
        directory.display().to_string().replace('\'', "'\\''"),
        bundle.duration_ms,
    ))
}

/// A herdr agent name for the bundle (`[a-z][a-z0-9_-]{0,31}`), unique per bundle.
pub fn agent_name(bundle_dir: &Path) -> String {
    let stem = bundle_dir
        .file_stem()
        .map(|s| s.to_string_lossy().to_lowercase())
        .unwrap_or_default();
    let tail: String = stem
        .trim_start_matches("scrawl-")
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect();
    // The end of the ID (milliseconds, pid, counter) is what differs between bundles.
    let keep = tail.len().saturating_sub(25);
    format!("scrawl-{}", tail[keep..].trim_start_matches('-'))
}

/// `true` when `cwd` is `folder` or inside it.
fn in_folder(cwd: &str, folder: &Path) -> bool {
    Path::new(cwd).starts_with(folder)
}

fn str_at<'a>(value: &'a Value, keys: &[&str]) -> Option<&'a str> {
    keys.iter()
        .try_fold(value, |v, key| v.get(key))
        .and_then(Value::as_str)
}

/// A pane ID in a creation response: `root_pane` as an object with `pane_id`, or as a string.
fn root_pane(result: &Value) -> Option<String> {
    let pane = result.get("root_pane")?;
    pane.get("pane_id")
        .and_then(Value::as_str)
        .or_else(|| pane.as_str())
        .map(str::to_owned)
}

fn args(items: &[&str]) -> Vec<String> {
    items.iter().map(|s| (*s).to_owned()).collect()
}

/// An idle agent working in `folder`, for `"reuse"` mode.
fn idle_agent(herdr: &mut dyn Herdr, folder: &Path, kind: &str) -> Result<Option<String>> {
    let list = herdr.call(&args(&["agent", "list"]))?;
    let agents = list
        .get("agents")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    Ok(agents
        .iter()
        .filter(|a| str_at(a, &["agent"]) == Some(kind))
        .filter(|a| matches!(str_at(a, &["agent_status"]), Some("idle" | "done")))
        .filter(|a| str_at(a, &["cwd"]).is_some_and(|cwd| in_folder(cwd, folder)))
        .find_map(|a| str_at(a, &["pane_id"]).map(str::to_owned)))
}

/// A new shell pane in `folder`: a tab in the workspace already working there, else a new
/// workspace.
fn new_pane(herdr: &mut dyn Herdr, folder: &Path) -> Result<String> {
    let folder_text = folder.display().to_string();
    let panes = herdr.call(&args(&["pane", "list"]))?;
    let workspace = panes
        .get("panes")
        .and_then(Value::as_array)
        .and_then(|panes| {
            // The pane exactly in the folder first, then one inside it.
            let pick = |exact: bool| {
                panes.iter().find_map(|p| {
                    let cwd = str_at(p, &["cwd"])?;
                    let hit = if exact {
                        Path::new(cwd) == folder
                    } else {
                        in_folder(cwd, folder)
                    };
                    hit.then(|| str_at(p, &["workspace_id"]).map(str::to_owned))
                        .flatten()
                })
            };
            pick(true).or_else(|| pick(false))
        });
    let created = match workspace {
        Some(workspace) => herdr.call(&args(&[
            "tab",
            "create",
            "--workspace",
            &workspace,
            "--cwd",
            &folder_text,
            "--label",
            "scrawl",
            "--no-focus",
        ]))?,
        None => {
            let label = folder
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_else(|| "scrawl".into());
            herdr.call(&args(&[
                "workspace",
                "create",
                "--cwd",
                &folder_text,
                "--label",
                &label,
                "--no-focus",
            ]))?
        }
    };
    root_pane(&created).context("herdr didn't say which pane it created")
}

/// Forwards `bundle_dir` to a herdr agent in `folder` with `text` as the prompt.
pub fn forward_with(
    herdr: &mut dyn Herdr,
    bundle_dir: &Path,
    folder: &Path,
    text: &str,
    settings: &Settings,
) -> Result<()> {
    let mut marker = Marker::read(bundle_dir);
    if marker.prompted {
        return Ok(());
    }

    // The agent an earlier try started, if it's still there.
    let mut target = marker
        .agent
        .clone()
        .filter(|agent| herdr.call(&args(&["agent", "get", agent])).is_ok());

    if target.is_none() && settings.mode == Mode::Reuse {
        target = idle_agent(herdr, folder, &settings.kind)?;
    }

    if target.is_none() {
        let pane = match marker.pane_id.clone() {
            // A pane an earlier try opened but couldn't start the agent in.
            Some(pane) if herdr.call(&args(&["pane", "get", &pane])).is_ok() => pane,
            _ => {
                let pane = new_pane(herdr, folder)?;
                marker.pane_id = Some(pane.clone());
                marker.write(bundle_dir)?;
                pane
            }
        };
        let name = agent_name(bundle_dir);
        let mut start = args(&[
            "agent",
            "start",
            &name,
            "--kind",
            &settings.kind,
            "--pane",
            &pane,
        ]);
        if !settings.agent_args.is_empty() {
            start.push("--".into());
            start.extend(settings.agent_args.iter().cloned());
        }
        let poll = (settings.pane_ready / 20).min(std::time::Duration::from_millis(250));
        let mut waited = std::time::Duration::ZERO;
        loop {
            match herdr.call(&start) {
                Ok(_) => break,
                Err(err)
                    if format!("{err:#}").contains("agent_pane_busy")
                        && waited < settings.pane_ready =>
                {
                    std::thread::sleep(poll);
                    waited += poll;
                }
                Err(err) => return Err(err),
            }
        }
        marker.agent = Some(name.clone());
        marker.write(bundle_dir)?;
        target = Some(name);
        std::thread::sleep(settings.settle);
    }

    let target = target.expect("a target was chosen or started");
    // Typing the prompt into a question would answer it (Claude's folder-trust dialog defaults
    // to "No, exit"). Leave it for the retry, which prompts once the question is answered.
    let status = herdr.call(&args(&["agent", "get", &target]))?;
    let status = str_at(&status, &["agent", "agent_status"]).or(str_at(&status, &["agent_status"]));
    if status == Some("blocked") {
        bail!(
            "{target} is waiting on a question in herdr (folder trust?); answer it and Scrawl retries"
        );
    }
    herdr.call(&args(&["agent", "prompt", &target, text]))?;
    marker.prompted = true;
    marker.write(bundle_dir)?;
    scrawl_log(format!("herdr: prompted {target} in {}", folder.display()));
    if settings.focus {
        let _ = herdr.call(&args(&["agent", "focus", &target]));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;

    /// Records every call and answers from a script; an `Err` string fails that call.
    #[derive(Default)]
    struct Fake {
        calls: Vec<Vec<String>>,
        answers: VecDeque<std::result::Result<Value, String>>,
    }

    impl Fake {
        fn answering(answers: Vec<std::result::Result<Value, String>>) -> Self {
            Self {
                calls: Vec::new(),
                answers: answers.into(),
            }
        }
        fn commands(&self) -> Vec<String> {
            self.calls
                .iter()
                .map(|c| c[..2.min(c.len())].join(" "))
                .collect()
        }
    }

    impl Herdr for Fake {
        fn call(&mut self, args: &[String]) -> Result<Value> {
            self.calls.push(args.to_vec());
            match self.answers.pop_front() {
                Some(Ok(value)) => Ok(value),
                Some(Err(error)) => bail!(error),
                None => Ok(Value::Null),
            }
        }
    }

    fn settings(mode: Mode) -> Settings {
        Settings {
            kind: "claude".into(),
            mode,
            agent_args: vec![],
            focus: false,
            settle: std::time::Duration::ZERO,
            pane_ready: std::time::Duration::ZERO,
        }
    }

    /// Removes the test's folder when dropped.
    struct Tmp(PathBuf);
    impl Drop for Tmp {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn bundle() -> (Tmp, PathBuf) {
        let root = std::env::temp_dir().join(format!("scrawl-herdr-{}", crate::make_bundle_id()));
        let dir = root.join("scrawl-1793548083728-1db1-32.scrawl");
        std::fs::create_dir_all(&dir).unwrap();
        (Tmp(root), dir)
    }

    fn panes(list: &[(&str, &str)]) -> Value {
        serde_json::json!({ "panes": list.iter().map(|(ws, cwd)| serde_json::json!({
            "workspace_id": ws, "cwd": cwd, "pane_id": format!("{ws}:p1"),
        })).collect::<Vec<_>>() })
    }

    #[test]
    fn fresh_opens_a_tab_in_the_folders_workspace_starts_and_prompts() {
        let (_tmp, dir) = bundle();
        let mut herdr = Fake::answering(vec![
            Ok(panes(&[("w1", "/dev/other"), ("w5H", "/dev/vox.md")])),
            Ok(serde_json::json!({ "tab": {}, "root_pane": { "pane_id": "w5H:p9" } })),
            Ok(Value::Null),
            Ok(Value::Null),
        ]);
        forward_with(
            &mut herdr,
            &dir,
            Path::new("/dev/vox.md"),
            "hi",
            &settings(Mode::Fresh),
        )
        .unwrap();
        assert_eq!(
            herdr.commands(),
            [
                "pane list",
                "tab create",
                "agent start",
                "agent get",
                "agent prompt"
            ]
        );
        assert!(
            herdr.calls[1]
                .windows(2)
                .any(|w| w == ["--workspace", "w5H"])
        );
        assert!(herdr.calls[1].contains(&"--no-focus".to_owned()));
        let name = agent_name(&dir);
        assert_eq!(herdr.calls[2][2], name);
        assert!(herdr.calls[2].windows(2).any(|w| w == ["--pane", "w5H:p9"]));
        assert_eq!(herdr.calls[4][2..], [name.clone(), "hi".to_owned()]);
        let marker = Marker::read(&dir);
        assert!(marker.prompted);
        assert_eq!(marker.agent.as_deref(), Some(name.as_str()));
    }

    #[test]
    fn a_folder_without_a_workspace_gets_one() {
        let (_tmp, dir) = bundle();
        let mut herdr = Fake::answering(vec![
            Ok(panes(&[("w1", "/dev/other")])),
            Ok(serde_json::json!({ "workspace": {}, "root_pane": "w9:p1" })),
        ]);
        forward_with(
            &mut herdr,
            &dir,
            Path::new("/dev/vox.md"),
            "hi",
            &settings(Mode::Fresh),
        )
        .unwrap();
        assert_eq!(herdr.calls[1][..2], ["workspace", "create"]);
        assert!(
            herdr.calls[1]
                .windows(2)
                .any(|w| w == ["--label", "vox.md"])
        );
        assert!(herdr.calls[2].windows(2).any(|w| w == ["--pane", "w9:p1"]));
    }

    #[test]
    fn agent_args_follow_a_separator() {
        let (_tmp, dir) = bundle();
        let mut herdr = Fake::answering(vec![
            Ok(panes(&[])),
            Ok(serde_json::json!({ "root_pane": "w9:p1" })),
        ]);
        let mut s = settings(Mode::Fresh);
        s.agent_args = vec!["--permission-mode".into(), "acceptEdits".into()];
        forward_with(&mut herdr, &dir, Path::new("/dev/x"), "hi", &s).unwrap();
        let start = &herdr.calls[2];
        let sep = start.iter().position(|a| a == "--").unwrap();
        assert_eq!(start[sep + 1..], ["--permission-mode", "acceptEdits"]);
    }

    #[test]
    fn a_retry_after_a_failed_prompt_reuses_the_agent() {
        let (_tmp, dir) = bundle();
        let mut first = Fake::answering(vec![
            Ok(panes(&[])),
            Ok(serde_json::json!({ "root_pane": "w9:p1" })),
            Ok(Value::Null),
            Ok(Value::Null),
            Err("agent_prompt_stalled".into()),
        ]);
        assert!(
            forward_with(
                &mut first,
                &dir,
                Path::new("/dev/x"),
                "hi",
                &settings(Mode::Fresh)
            )
            .is_err()
        );
        let mut retry = Fake::default();
        forward_with(
            &mut retry,
            &dir,
            Path::new("/dev/x"),
            "hi",
            &settings(Mode::Fresh),
        )
        .unwrap();
        assert_eq!(retry.commands(), ["agent get", "agent get", "agent prompt"]);
        // Once prompted, a further retry does nothing.
        let mut again = Fake::default();
        forward_with(
            &mut again,
            &dir,
            Path::new("/dev/x"),
            "hi",
            &settings(Mode::Fresh),
        )
        .unwrap();
        assert!(again.calls.is_empty());
    }

    #[test]
    fn a_blocked_agent_is_not_prompted_until_it_is_answered() {
        let (_tmp, dir) = bundle();
        let blocked = serde_json::json!({ "agent": { "agent_status": "blocked" } });
        let mut first = Fake::answering(vec![
            Ok(panes(&[])),
            Ok(serde_json::json!({ "root_pane": "w9:p1" })),
            Ok(Value::Null),
            Ok(blocked.clone()),
        ]);
        let error = forward_with(
            &mut first,
            &dir,
            Path::new("/dev/x"),
            "hi",
            &settings(Mode::Fresh),
        )
        .unwrap_err();
        assert!(format!("{error:#}").contains("waiting on a question"));
        assert!(!first.commands().contains(&"agent prompt".to_owned()));
        // Still blocked on the retry: still not prompted.
        let mut still = Fake::answering(vec![Ok(blocked.clone()), Ok(blocked)]);
        assert!(
            forward_with(
                &mut still,
                &dir,
                Path::new("/dev/x"),
                "hi",
                &settings(Mode::Fresh)
            )
            .is_err()
        );
        // Answered: the retry prompts the same agent.
        let idle = serde_json::json!({ "agent": { "agent_status": "idle" } });
        let mut answered = Fake::answering(vec![Ok(idle.clone()), Ok(idle)]);
        forward_with(
            &mut answered,
            &dir,
            Path::new("/dev/x"),
            "hi",
            &settings(Mode::Fresh),
        )
        .unwrap();
        assert_eq!(
            answered.commands(),
            ["agent get", "agent get", "agent prompt"]
        );
        assert!(Marker::read(&dir).prompted);
    }

    #[test]
    fn a_new_pane_is_given_time_to_reach_its_prompt() {
        let (_tmp, dir) = bundle();
        let busy = || Err("herdr agent: {\"code\":\"agent_pane_busy\"}".to_owned());
        let mut herdr = Fake::answering(vec![
            Ok(panes(&[])),
            Ok(serde_json::json!({ "root_pane": "w9:p1" })),
            busy(),
            busy(),
            Ok(Value::Null),
        ]);
        let mut s = settings(Mode::Fresh);
        s.pane_ready = std::time::Duration::from_millis(5);
        forward_with(&mut herdr, &dir, Path::new("/dev/x"), "hi", &s).unwrap();
        assert_eq!(
            herdr.commands(),
            [
                "pane list",
                "workspace create",
                "agent start",
                "agent start",
                "agent start",
                "agent get",
                "agent prompt"
            ]
        );
        // Other start errors aren't retried.
        let (_tmp, dir) = bundle();
        let mut herdr = Fake::answering(vec![
            Ok(panes(&[])),
            Ok(serde_json::json!({ "root_pane": "w9:p1" })),
            Err("unsupported interactive agent kind".into()),
        ]);
        assert!(forward_with(&mut herdr, &dir, Path::new("/dev/x"), "hi", &s).is_err());
        assert_eq!(herdr.commands().len(), 3);
    }

    #[test]
    fn a_retry_after_a_failed_start_reuses_the_pane() {
        let (_tmp, dir) = bundle();
        let mut first = Fake::answering(vec![
            Ok(panes(&[])),
            Ok(serde_json::json!({ "root_pane": "w9:p1" })),
            Err("agent_start_timeout".into()),
        ]);
        assert!(
            forward_with(
                &mut first,
                &dir,
                Path::new("/dev/x"),
                "hi",
                &settings(Mode::Fresh)
            )
            .is_err()
        );
        let mut retry = Fake::default();
        forward_with(
            &mut retry,
            &dir,
            Path::new("/dev/x"),
            "hi",
            &settings(Mode::Fresh),
        )
        .unwrap();
        assert_eq!(
            retry.commands(),
            ["pane get", "agent start", "agent get", "agent prompt"]
        );
        assert!(retry.calls[1].windows(2).any(|w| w == ["--pane", "w9:p1"]));
    }

    #[test]
    fn reuse_prompts_an_idle_agent_in_the_folder() {
        let (_tmp, dir) = bundle();
        let agents = serde_json::json!({ "agents": [
            { "agent": "claude", "agent_status": "working", "cwd": "/dev/vox.md", "pane_id": "w5H:p2" },
            { "agent": "codex", "agent_status": "idle", "cwd": "/dev/vox.md", "pane_id": "w5H:p3" },
            { "agent": "claude", "agent_status": "idle", "cwd": "/dev/other", "pane_id": "w1:p1" },
            { "agent": "claude", "agent_status": "done", "cwd": "/dev/vox.md/sub", "pane_id": "w5H:p5" },
        ]});
        let mut herdr = Fake::answering(vec![Ok(agents)]);
        forward_with(
            &mut herdr,
            &dir,
            Path::new("/dev/vox.md"),
            "hi",
            &settings(Mode::Reuse),
        )
        .unwrap();
        assert_eq!(
            herdr.commands(),
            ["agent list", "agent get", "agent prompt"]
        );
        assert_eq!(herdr.calls[2][2], "w5H:p5");
    }

    #[test]
    fn reuse_without_an_idle_agent_starts_one() {
        let (_tmp, dir) = bundle();
        let mut herdr = Fake::answering(vec![
            Ok(serde_json::json!({ "agents": [] })),
            Ok(panes(&[])),
            Ok(serde_json::json!({ "root_pane": "w9:p1" })),
        ]);
        forward_with(
            &mut herdr,
            &dir,
            Path::new("/dev/x"),
            "hi",
            &settings(Mode::Reuse),
        )
        .unwrap();
        assert_eq!(
            herdr.commands(),
            [
                "agent list",
                "pane list",
                "workspace create",
                "agent start",
                "agent get",
                "agent prompt"
            ]
        );
    }

    #[test]
    fn apps_map_to_folders_by_longest_prefix() {
        let apps = vec![
            ("bontecou.".to_owned(), PathBuf::from("/dev/all")),
            ("bontecou.Voxboard".to_owned(), PathBuf::from("/dev/vox.md")),
        ];
        assert_eq!(
            folder_for_app(Some("bontecou.voxboard"), &apps, None),
            Some(PathBuf::from("/dev/vox.md"))
        );
        assert_eq!(
            folder_for_app(Some("bontecou.Sync-md"), &apps, None),
            Some(PathBuf::from("/dev/all"))
        );
        assert_eq!(
            folder_for_app(Some("com.other"), &apps, Some(PathBuf::from("/d"))),
            Some(PathBuf::from("/d"))
        );
        assert_eq!(folder_for_app(None, &apps, None), None);
    }

    #[test]
    fn sessions_round_trip_and_reject_opencode_ids() {
        let (session, title) = session_for(Path::new("/Users/me/dev/vox.md"));
        assert_eq!(session, "herdr:/Users/me/dev/vox.md");
        assert_eq!(title, "herdr · vox.md");
        assert_eq!(
            folder_of(&session),
            Some(PathBuf::from("/Users/me/dev/vox.md"))
        );
        assert_eq!(folder_of("ses_abc"), None);
        assert_eq!(folder_of("herdr:relative"), None);
    }

    #[test]
    fn agent_names_are_valid_and_differ() {
        let a = agent_name(Path::new("/c/scrawl-1793548083728-1db1-32.scrawl"));
        let b = agent_name(Path::new("/c/scrawl-1793548083728-1db1-33.scrawl"));
        assert_ne!(a, b);
        for name in [&a, &b] {
            assert!(name.len() <= 32, "{name}");
            assert!(name.starts_with(|c: char| c.is_ascii_lowercase()));
            assert!(
                name.chars()
                    .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-' || c == '_')
            );
        }
    }
}

/// Drives a real herdr server: `SCRAWL_HERDR_LIVE_DIR=<folder> cargo test herdr::live -- --ignored`.
/// Starts an agent in a new workspace or tab in that folder and prompts it with a reply-only
/// message. Never run by default.
#[cfg(test)]
mod live {
    use super::*;

    #[test]
    #[ignore]
    fn live_smoke() {
        let folder =
            PathBuf::from(std::env::var("SCRAWL_HERDR_LIVE_DIR").expect("SCRAWL_HERDR_LIVE_DIR"));
        let bundle = folder.join(format!("{}.scrawl", crate::make_bundle_id()));
        std::fs::create_dir_all(&bundle).unwrap();
        let mut cli = Cli::locate(None).unwrap();
        let settings = Settings {
            kind: std::env::var("SCRAWL_HERDR_LIVE_KIND").unwrap_or_else(|_| "claude".into()),
            mode: Mode::Fresh,
            agent_args: vec![],
            focus: false,
            settle: std::time::Duration::from_millis(1500),
            pane_ready: std::time::Duration::from_secs(10),
        };
        forward_with(
            &mut cli,
            &bundle,
            &folder,
            "Scrawl herdr smoke test. Reply with just OK. Do not run any tools.",
            &settings,
        )
        .unwrap();
        let marker = Marker::read(&bundle);
        println!("marker: {marker:?}");
        assert!(marker.prompted);
    }
}
