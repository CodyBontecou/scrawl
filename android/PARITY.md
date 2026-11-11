# ScrawlKit for Android: parity spec

Source of truth: `Sources/ScrawlKit/*.swift` (iOS), `Tests/ScrawlKitTests/*`, `README.md` (iPhone /
ScrawlKit sections), `docs/iphone-and-routing.md`, and the Mac receiver in `src/phone.rs` +
`src/delivery.rs`. Where the README and the code disagree, **the code wins** (noted inline).

Conventions in this document:

- "pt" = iOS point. On Android use **dp** wherever iOS uses points (timeline coordinates, UI
  metrics, thresholds). `screen.scale` becomes `DisplayMetrics.density`.
- "s" = seconds, "ms" = milliseconds. **All timeline times are seconds (Double), rounded to 3
  decimals** (`round(v * 1000) / 1000`).
- Quoted strings are exact and user-visible (keep them byte-identical, including `…` U+2026,
  `·` U+00B7, `›` U+203A).
- `[A]` marks an iOS-only mechanism that needs an Android design (proposals included).

---

## 0. Scope and shape of the port

ScrawlKit records the host app (video + microphone narration), every touch (with the label of the
element hit), freehand red ink strokes, markers, app events, screen names and app context, all on
one clock, and delivers `movie.mp4 + timeline.json` to Scrawl on a Mac (LAN HTTP, USB fallback) or
to an HTTPS endpoint (durable outbox). It is **completely inert until paired**.

Proposed Android module layout (suggestion, not parity-critical):

| iOS file | Android equivalent |
| --- | --- |
| `Scrawl.swift` (`ScrawlConfig`, `Scrawl`) | `ScrawlConfig.kt`, `Scrawl.kt` (singleton `object Scrawl` or `Scrawl.shared`) |
| `ScrawlModifier.swift` | `Scrawl.install(application, trigger, isEnabled)` + `Touches.kt` (window callback hook); `ScrawlCompose.kt` (`Modifier.scrawlScreen` / `scrawlContext`) |
| `ScrawlContext.swift` | `ScrawlAppContext.kt` + Compose `ScrawlScreen(...)`, `ScrawlContext(...)` effects |
| `Recorder.swift` | `ScrawlRecorder` interface, `MediaProjectionRecorder` |
| `AppFrameRecorder.swift` | `AppFrameRecorder.kt`, `MovieWriter.kt` (MediaCodec+MediaMuxer), `MicrophoneCapture.kt` (AudioRecord) |
| `FramePacer.swift` | `FramePacer.kt` (pure, port 1:1 with tests) |
| `Keyboard.swift` | `KeyboardTracker.kt` (WindowInsets IME), `KeyboardPanel.kt` |
| `Ink.swift` | `Ink.kt` (pure: `OneEuroFilter`, `InkStrokeBuilder`, `InkTrace`, `InkFade`; port 1:1 with tests) |
| `InkView.swift` | `InkView.kt` (custom View or Compose Canvas) |
| `Overlay.swift` | `OverlayHost.kt` |
| `ScrawlStrip.swift` | `ScrawlStrip.kt`, `LiquidStrip.kt` (spring model), `StripPlacement.kt` |
| `Delivery.swift` | `IngestReply.kt`, `DeliveryTracker.kt` (pure, port with tests) |
| `Outbox.swift` | `Outbox.kt` (+ WorkManager/JobScheduler worker) |
| `EditingGestures.swift` | N/A (see §4.4) |
| `StartupTrace.swift` | `StartupTrace.kt` |

Pure-logic types (`FramePacer`, `Ink*`, `DeliveryTracker`, `IngestReply`, `Outbox.classify/backoff/
reportsProgress`, `ScrawlConfig.parse*`) must be ported with their unit tests
(`Tests/ScrawlKitTests/*`) translated to JUnit; they are the parity oracle.

---

## 1. Public API surface

### 1.1 `ScrawlConfig` (value type)

```
data class ScrawlConfig(urls: List<Uri/URL>, token: String, sessionID: String? = null)
```

- Constructor normalizes: `urls = validBaseURLs(urls)`, `token = token.trim()` (whitespace +
  newlines), `sessionID = trimmed, or null if empty`.
- `isValid` = `urls.isNotEmpty() && token.isNotEmpty()`.
- `static keychainService = "dev.kitlangton.scrawl"` (public on iOS; keep as the storage
  namespace constant).
- `static loadFromKeychain(): ScrawlConfig?` → Android `loadFromStore(context)`.
- `static saveToKeychain(config): Boolean` → `saveToStore`. Normalizes first; refuses (returns
  false) if not valid.
- `static deleteFromKeychain()` → `deleteFromStore`.
- Internal: `parseSetupURL`, `parseHTTPBaseURL`, `validBaseURLs`, `resolved(...)` (see §2).
- Serialized form (iOS Codable JSON in the Keychain item):
  `{"urls":["http://…"],"token":"…","sessionID":"ses_…"}` (`sessionID` omitted when null).
  On load, the decoded value is re-normalized and returned only if valid.

### 1.2 `Scrawl` (main-thread singleton, observable)

Observable state (iOS `@Observable`; Android: expose as `StateFlow`s or Compose `State`):

| Member | Type | Semantics |
| --- | --- | --- |
| `Scrawl.shared` | singleton | |
| `isRecording` | read-only Bool | Recorder confirmed started (first frame captured + audio ready). |
| `isStarting` | read-only Bool | Recorder starting (permission prompt, encoder setup); overlay already up; touches/ink already accepted. |
| `isDrawing` | read-only Bool | Ink mode on. |
| `uploadStatus` | read-only `UploadStatus` | `idle`, `sending`, `sent(IngestReply)`, `queued`, `failed`. |
| `startedAt` | read-only Date? | Recording start (movie t=0) once started; provisional value while starting; nil otherwise. |
| `markers` | read-only `[Double]` | Seconds into recording. |
| `completedStrokes` | read-only `[[Point]]` | Smoothed points of strokes drawn this recording (cleared at finish). |
| `activeStroke` | deprecated, always empty | Keep for source compat or drop (Kotlin: drop or `@Deprecated`). |
| `recordings` | read-only `[Recording]` | LAN-path recordings this process, newest last (see §9.6). |
| `Recording` | `{ movie: File/URL, timeline: File/URL }` | |
| `narration` | var Bool = **true** | Record the microphone. |
| `captureMethod` | var `ScrawlCaptureMethod` = **`.appFrames`** | Takes effect on next recording. |
| `suppressesSystemEditingGestures` | var Bool = true | iOS-only (see §4.4). Keep property; on Android a no-op (document). |
| `sharesWhenDone` | var Bool = **true** | If false, the failed bubble's Share just dismisses. |
| `serverURLs` | var `[URL]` | Settable; setting posts configuration-changed. |
| `token` | var String? | Settable; posts configuration-changed. |
| `sessionID` | var String? | |
| `isConfigured` (static + instance) | Bool | `serverURLs.isNotEmpty() && token?.trim().isNotEmpty() == true`. |
| `startupObserver` | `((ScrawlStartupEvent) -> Unit)?` | Opt-in startup timing (§6.9). Called on the stage's own thread; keep cheap. |
| `timelineSupplement` | `suspend (Map<String,Any?>) -> Map<String,Any?>`? | Called on main after the movie is written; returned keys **overwrite** timeline keys. |
| `onRecordingSubmitted` | `suspend (timeline: File, movie: File) -> Boolean`? | Called when a finished (non-discarded) recording is handed to delivery. `true` = handled in-process: files deleted, status idle, overlay leaves, no upload. |

Static / instance functions:

| Function | Semantics |
| --- | --- |
| `Scrawl.handleSetupURL(url): Boolean` | §2.1. Returns true iff `url` was a valid Scrawl setup/reset link. |
| `Scrawl.event(label)` | Appends `{"t", "label"}` to `events` if recording/starting (`startedAt != nil`), `t` = wall-clock seconds since `startedAt`. Must be called on main. |
| `Scrawl.screen(name?)` | §8. |
| `Scrawl.context(key, value?)` | §8. |
| `toggle()` | No-op unless configured. Recording or starting → `stop()`, else `start()`. **No haptic.** |
| `toggleDrawing()` | Only while recording/starting; flips `isDrawing`; light impact haptic. |
| `start()` | No-op unless configured and not recording/starting. Starts with a startup trace iff `startupObserver` set. |
| `stop()` | Finish and send (§6.2). |
| `discard()` | Only while recording/starting; light haptic; finish with discard (Undo window, §7.6). |
| `mark()` | Only with `startedAt`; appends seconds-since-start to `markers`; medium haptic. **No strip button calls it** (API only). |
| `prepareToStart()` | Cheap prewarm (§4.3). Public. |
| `setTeamEndpoint(url?, token?)` | Non-null url + non-blank token → in-memory override `ScrawlConfig(urls:[url], token, session:nil)` (does **not** touch stored config). Null → restore `ScrawlConfig.resolved()`. While set, every `applyConfig` uses the team endpoint instead. |
| `Scrawl.backgroundSessionIdentifier` | `"dev.kitlangton.scrawl.outbox.<bundleId or "app">"`. Android: use as WorkManager unique-work name / tag. |
| `Scrawl.handleBackgroundURLSessionEvents(identifier, completion): Boolean` | [A] iOS relaunch hook. Android: not needed (WorkManager survives process death); keep a no-op that returns `identifier == backgroundSessionIdentifier` for API parity, or omit. |

`ScrawlCaptureMethod` (rawValue strings go into the timeline's `capture`):
- `appFrames` → `"appFrames"`: draws the app's own windows. Default.
- `replayKit` → `"replayKit"`: system screen capture. [A] Android: `MediaProjection`. Recommended
  Kotlin name `screenCapture` with raw value `"mediaProjection"` (the Mac ignores `capture`, so any
  string is safe; keep `"appFrames"` exact).

`ScrawlTrigger`: `threeFingerLongPress` (default), `none` (host calls `start()`/`stop()`).

`IngestReply` (public): `delivered(sessionTitle)`, `queued(sessionTitle)`,
`processing(sessionTitle)`, `saved`; `isDelivered`.

`ScrawlStartupEvent` (public, Codable): `stage: String`, `milliseconds: Double`,
`onMainThread: Boolean`.

### 1.3 View attachment (`.scrawl()`)

iOS: `View.scrawl(trigger: ScrawlTrigger = .threeFingerLongPress, isEnabled: Bool = true)` on the
root view. It (a) installs a hidden installer view that attaches a touch observer and the trigger
recognizer **to its window** only when `isEnabled && Scrawl.isConfigured && window != nil`,
re-syncing on window change, parameter change and the configuration-changed notification; (b)
registers `.onOpenURL { if isEnabled { Scrawl.handleSetupURL(url) } }` (non-consuming: the host's
own URL handlers still receive the link).

Android design:
- As built: `Scrawl.install(application, trigger = ThreeFingerLongPress, isEnabled = true)`, which
  uses `ActivityLifecycleCallbacks` to install on every app window (activities, dialogs, popups).
  Android has no per-root-view attachment point like `.scrawl()`, so there is no
  `install(activity)` overload or `ScrawlHost` composable; `isEnabled = false` gates touches and
  setup links exactly as `.scrawl(isEnabled: false)` does. Compose gets `Modifier.scrawlScreen` and
  `Modifier.scrawlContext` (SwiftUI's `.scrawlScreen` / `.scrawlContext`).
- Touch observation: wrap the Activity `Window.Callback` and observe `dispatchTouchEvent` (always
  forward, never consume, never delay). Install/uninstall must follow `isConfigured` exactly like
  iOS (unconfigured → no wrapper installed at all).
- Setup links: the host declares an intent filter for `<scheme>://scrawl` (and legacy
  `<scheme>://tether`); ScrawlKit provides `Scrawl.handleSetupIntent(intent)` /
  `Scrawl.handleSetupURL(uri)` to call from `onCreate`/`onNewIntent`, and `install()` should check
  the launching Activity's intent automatically (non-consuming).
- Per-window trigger count (`threeFingerTriggers`) increments when a trigger is installed and
  decrements on uninstall; prewarm only fires when > 0.

### 1.4 Context API (see §8 for semantics)

- `Scrawl.screen(name: String?)`, `Scrawl.context(key: String, value: String?)` (static, "manual"
  token).
- iOS view modifiers `.scrawlScreen(name)`, `.scrawlContext(key, value)`. Android:
  `@Composable fun ScrawlScreen(name: String)` and `@Composable fun ScrawlContext(key, value?)`
  (each with a `remember { UUID }` token and `DisposableEffect`), plus View-system helpers
  `View.scrawlScreen(name)` using attach/detach listeners.

---

## 2. Setup / pairing

### 2.1 Setup URL grammar

```
<scheme>://scrawl?url=<urlencoded base>[&url=<second>…]&token=<token>[&session=ses_…]
<scheme>://scrawl?reset=1
```

Parsing rules (`parseSetupURL`):
1. Scheme must be present and non-empty (any scheme).
2. Host, lowercased, must be `scrawl` or `tether` (legacy).
3. Must have query items. If **any** item `reset` has value `"1"` or (case-insensitive) `"true"` →
   **reset** (wins over everything else).
4. Every `url` item (in order) → `parseHTTPBaseURL`; invalid ones dropped; then `validBaseURLs`
   (dedupe).
5. `token` = first `token` item, trimmed (whitespace+newlines), else `""`.
6. `session` = first `session` item, trimmed; empty → nil.
7. Build `ScrawlConfig`; if not valid (no URLs or empty token) → **not a Scrawl link** (return
   false, nothing changes).

`parseHTTPBaseURL(raw)`: trim whitespace/newlines; strip **all** trailing `/`; must be non-empty,
parse as URL, scheme (lowercased) `http` or `https`, non-empty host. Returns the trimmed URL
(case of scheme/host preserved as given). `validBaseURLs`: normalize each via its absolute string,
drop invalid, dedupe by normalized absolute string preserving first-seen order.

`handleSetupURL(url)`:
- Parse; if nil → return false.
- Duplicate suppression: if the same URL was handled < **250 ms** ago, it is still applied but
  **no haptic** (SwiftUI may deliver one link twice).
- Configure: save to the secure store, then `applyConfig(resolved(store: newConfig) ?: newConfig)`
  (launch overrides still overlay); **success** notification haptic.
- Reset: delete stored config (current + legacy), `applyConfig(resolved(store: null))`; **warning**
  notification haptic.
- Return true.

`applyConfig(config)`: effective = team endpoint override if set, else `config`. Sets
`serverURLs`, `token`, `sessionID`. If now **not configured**: if recording/starting, hard-stop
without sending (`isRecording = isStarting = isDrawing = false`, `startedAt = nil`,
`pendingFinish = nil`, `recorder.cancel()`), finalize any pending discard, hide overlay. Then post
configuration-changed (on the **next main-loop turn**, iOS avoids init re-entrancy; Android:
`Handler(mainLooper).post`). The configuration-changed handler runs `syncSystemIntegration()`
(editing gestures, keyboard tracker, outbox) and notifies installers.

Mac side producing the link: `scrawl link --scheme <scheme> [--session ses_…]` →
`format_setup_link` (`src/phone.rs`): scheme validated `[A-Za-z0-9+-.]`, URLs normalized
(http/https, trailing `/` trimmed, deduped), each `url=` query-escaped, then `token=`, then optional
`session=`. Default URLs: Tailscale IPv4 then `en0`/`en1` LAN IPs on port **47777**. A QR code is
printed; Android's camera/QR scanner opens the deep link. No Mac change needed for Android pairing.

### 2.2 Storage [A]

iOS: Keychain generic password, service `"dev.kitlangton.scrawl"`, account `"config"`, value =
JSON of `ScrawlConfig`, accessibility `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. Save =
update, else add. Legacy service `"dev.kitlangton.tether"` is migrated on load (copied to the
current service, then deleted); delete removes both.

Android: store the same JSON encrypted with an Android Keystore AES-GCM key (Tink/`androidx.security`
or a hand-rolled Keystore wrapper; `EncryptedSharedPreferences` is deprecated) in a file under
`Context.noBackupFilesDir` (excluded from Auto Backup ⇒ "ThisDeviceOnly"). Name it after the
service: e.g. `noBackupFilesDir/dev.kitlangton.scrawl/config`. Legacy migration: not applicable
(no legacy Android installs), but keep accepting the `tether` host.

### 2.3 Launch argument / environment overrides [A]

`ScrawlConfig.resolved(arguments, environment, keychain)` overlays on the stored config:

- URLs: every `-scrawl-url <value>` argument (may repeat), each value split on `,`; **plus**
  `SCRAWL_URL` env (split on `,`). All parsed with `parseHTTPBaseURL` and deduped. If the
  resulting list is non-empty it **replaces** the stored URLs; otherwise stored URLs are used.
- Token: first `-scrawl-token <v>` (trimmed); else `SCRAWL_TOKEN` env if non-empty after trim.
  An empty override falls back to the stored token. (Note: if `-scrawl-token` is present but
  empty, env is not consulted.)
- Session: same pattern with `-scrawl-session` / `SCRAWL_SESSION`; empty → stored.
- Return the config only if valid, else nil.

Android has no process arguments for normal launches. Equivalent sources (in this priority):
1. Intent extras on the launch intent: `scrawl-url` (String, comma-separated, or String array),
   `scrawl-token`, `scrawl-session` (e.g. `adb shell am start -n pkg/.Main --es scrawl-url
   http://10.0.2.2:47777 --es scrawl-token …`).
2. Debug system properties / env are not reliably available; optionally read
   `BuildConfig`-provided defaults or a `debug.scrawl.*` system property for emulator testing.
Keep the same merge semantics (override replaces URLs; empty override falls back).

---

## 3. Inertness and gating

Unpaired (`!isConfigured`) devices: **no** touch observer, no trigger recognizer, no overlay
window, no keyboard observer, no editing-gesture override, no network calls, no files, no
background session/outbox. The only thing active is the deep-link handler (if `isEnabled`).
`toggle()`, `start()`, `triggerRecognized()`, `prepareToStart()` all no-op when unconfigured.

`syncSystemIntegration()` (runs after every configuration change):
- Editing-gesture suppression = `isConfigured && suppressesSystemEditingGestures &&
  threeFingerTriggers > 0` (iOS only).
- `KeyboardTracker.setTracking(isConfigured)`.
- `syncOutbox()` (§9.4): outbox exists iff configured with an `https` URL and a non-blank token.

DEBUG gating: the library itself has none (only SwiftUI previews are `#if DEBUG`). Hosts include
ScrawlKit in Debug builds only (e.g. vox.md "Add ScrawlKit to iOS Debug builds"). Android: ship as
a normal artifact meant for `debugImplementation`, plus an optional `scrawlkit-noop` artifact with
the same public API for `releaseImplementation`.

---

## 4. Activation

### 4.1 Three-finger long press (default trigger)

iOS `UILongPressGestureRecognizer`: `numberOfTouchesRequired = 3`, `minimumPressDuration = 0.5`
s, `allowableMovement` = UIKit default **10 pt**, `cancelsTouchesInView = false` (app still gets
the touches), recognized simultaneously with everything. Fires on state `.began` (i.e. after 0.5 s
held), only if configured → `triggerRecognized()`:
1. Create a `StartupTrace` (if `startupObserver` set and not already recording/starting).
2. **Success** notification haptic.
3. If recording or starting → `stop()` (send). Else → `start(trace)`.

Android: implement in the Window.Callback touch observer: when exactly 3 pointers are down
(ACTION_POINTER_DOWN brings count to 3) start a 500 ms timer; cancel if any pointer moves > 10 dp
from its down position, a pointer lifts, or a 4th pointer lands (UIKit requires exactly 3);
fire once per gesture. Never consume events.

### 4.2 `.none` trigger
No recognizer; touches still observed; host calls `start()`/`stop()`/`toggle()`.

### 4.3 Prewarm (`prepareToStart`)
The touch observer tracks fingers in its window; when a `began` brings the count to **exactly 3**
and `threeFingerTriggers > 0`, call `prepareToStart()`: only if configured, not recording/starting,
no overlay yet, and there is a foreground scene/activity. It creates the overlay window, lays it out
**transparently** (`alpha = 0`, unhidden, so the first layout isn't on the recognition frame), sets
`preparedAt = now`, emits startup event `"prewarm"` (ms of the main-thread work, onMainThread true),
and when the strip region is first laid out emits `"prewarmLayout"`. If not started within **2 s**,
the overlay is released (`hideOverlay`). While prepared, the strip mode is
`.recording(startedAt: preparedAt, isDrawing: false)` at alpha 0 (so it's already laid out).

### 4.4 System three-finger editing gestures [A]
iOS adds a method to `UIWindow` making `editingInteractionConfiguration` return `.none` (except
ScrawlKit's own overlay window) while suppression is active, so the three-finger press doesn't open
the Undo·Cut·Copy·Redo bar. **Android has no in-app equivalent.** Keep the
`suppressesSystemEditingGestures` property (no-op, documented). Note that some OEM skins (MIUI,
OxygenOS, One UI) bind three-finger swipe/long-press to screenshots system-wide; ScrawlKit cannot
suppress those. Document; consider allowing an alternative trigger later (out of parity scope).

### 4.5 Inert-when-unpaired check for triggers
`triggered()` re-checks `Scrawl.isConfigured`. The installer uninstalls everything when the config
becomes invalid (configuration-changed notification).

---

## 5. Touches, labels, hold haptic (timeline `touches`)

Recorded only while `isRecording || isStarting`, from the app window's touch observer (touches
on the overlay — strip, bubbles, ink — are **not** seen and not logged).

- **IDs**: small sequential ints per finger starting at **1**, reset (`touchIDs = {}`,
  `nextTouchID = 1`) at each `start`. Only touches whose `began` happens after start get an ID;
  fingers already down (the trigger's three) are ignored for their whole life. When every finger
  lifts (`reset()` of the observer) → `touchesReset()` clears id map, labels, hold starts and
  dragging set (`nextTouchID` is **not** reset, so IDs keep increasing within a recording).
- **Phases**: `"down"`, `"move"`, `"up"`, `"cancel"` (`"other"` for anything else; Android maps
  ACTION_DOWN/POINTER_DOWN → down, MOVE → move, UP/POINTER_UP → up, CANCEL → cancel).
- **Entry**: `{"t": s, "id": Int, "x": pt, "y": pt, "phase": String, "label"?: String}`.
  `t` = **wall-clock** seconds since `startedAt` at processing time (not the event timestamp),
  rounded 3 dp. `x`,`y` = location in the touch's **window**, in points (Android: dp, relative to
  the window/decor view origin, rounded 3 dp). `label` only when non-empty; it's the label found at
  `down` and repeated on every entry of that touch.
- **Move throttling**: per touch ID, a `move` is dropped if < **1/30 s** after that ID's previous
  logged entry (any phase). `down`/`up`/`cancel` are never dropped.
- `lastTouchMoveMediaTime` = monotonic time of the latest move (feeds `FramePacer`), and the id is
  added to `draggingTouchIDs` (removed on up/cancel).
- `touchWindow` = the window of the latest touch; used for timeline `screen`.
- On `down`: walk up from the hit view collecting scroll views (`trackScrollViews`) for
  `isInteractingForCapture`.

**Hit label algorithm** (`hitLabel`, at `down`):
1. Leaf = window hit-test at the point.
2. Walk the leaf and up to **7** levels (leaf + ancestors, 7 iterations total):
   a. `accessibilityLabel` (cleaned) → return.
   b. Button: `currentTitle ?? configuration.title` (cleaned) → return.
   c. Label (`UILabel`): `text` (cleaned) → return.
   d. If the view's height ≤ **110 pt**: search descendants up to depth **3** for the nearest
      `UILabel` with text (skip hidden or `alpha <= 0.05` views): distance = distance from the
      point to the label's rect (0 inside); smallest wins → return.
3. Else nil.
`cleanedLabel`: collapse whitespace runs to single spaces (split on whitespace, join " "); empty →
nil; longer than **48** chars → first 48 + `"…"`.

Android [A]: View system: `contentDescription` → `TextView.text` (Buttons are TextViews) → nearest
child TextView within depth 3 in views ≤ 110 dp tall. **Compose** content is a single
`AndroidComposeView`; labels must come from semantics: use the semantics tree
(`SemanticsOwner`/`AccessibilityNodeInfo` via the view's `AccessibilityNodeProvider`) to find the
deepest node containing the point, then walk up merging `ContentDescription`/`Text`. This is a top
risk (internal APIs / accessibility-only providers); design it behind an interface with a View-only
fallback.

**Hold tick**: on `down`, remember the point; after **420 ms**, if still recording and the touch
hasn't moved > **12 pt** (Euclidean) and hasn't ended → **rigid** impact haptic. Moving > 12 pt or
any other phase cancels it. (These match the Mac's `TAP_MS = 420` and `TAP_SLOP_PT = 12`.)

`isInteractingForCapture` (for FramePacer): true if ink is actively being drawn, any touch is in
`draggingTouchIDs`, the main run loop is in tracking mode, or any tracked scroll view (visible, in a
window) is tracking/dragging/decelerating. Android: ink active || dragging touches || any tracked
`RecyclerView.scrollState != IDLE` / `ScrollView`/`NestedScrollView` flinging. Compose scroll state
isn't reachable generically; rely on touch movement + `settleDelay` (§6.5).

---

## 6. Recording

### 6.1 Start sequence (`start(trace:)`)

Preconditions: configured, not recording/starting. Steps, in order:
1. Cancel prewarm expiry; attach trace; `hosted = (any https URL configured)`.
2. If a discard is pending → finalize it (delete files now).
3. Reset per-recording state: markers, events, touches, strokes, `completedStrokes`, clear ink
   (not animated), touch maps, `nextTouchID = 1`, hold/drag/scroll tracking,
   `lastTouchMoveMediaTime`, `isDrawing = false`, `uploadStatus = idle`, failed recording/entry,
   `shownEntry = nil`, `delivery = nil`, stall check cancelled, `statusID += 1`.
4. `isStarting = true`, `pendingFinish = nil`, `startedAt = now` (provisional), `showOverlay()`,
   `preparedAt = nil`.
5. `recorder = makeRecorder(captureMethod, hosted)`; trace mark `recorderCreated`. If
   `!recorder.isAvailable` → `isStarting = false`, `startedAt = nil`, hide overlay, return
   (silent).
6. `recorder.onInterruption = { stop() }`.
7. After the overlay's **first presentation** (next display frame after it is shown, then one more
   main-queue hop), run `begin`:
   - If hosted: async `syncOutbox()` (marks `outboxBegin`/`outboxEnd`); if `pairingRevoked` show
     notice `linkExpired` for 5 s.
   - Async `findMac()` (§9.1) — also raises iOS's local-network prompt at a point the UI is up.
   - `recorder.start(microphone: narration)`; on completion (ignored if this recorder was replaced
     or no longer starting): `isStarting = false`;
     - success(`firstFrameDate`): mark `ready`; `shift = provisionalStartedAt − firstFrameDate`;
       rebase all early input by `shift` (markers, events `t`, touches `t`, strokes `t0/t1/fadeAt`
       and point times; each `max(0, t + shift)` rounded 3 dp); `startedAt = firstFrameDate`;
       `KeyboardTracker.recordingWillBegin()`; `ScrawlAppContext.begin(at: startedAt)`;
       `isRecording = true`; if hosted start the length limit (§6.8); if a finish was requested
       while starting (`pendingFinish`), run it now (send or discard).
     - failure: log `"Scrawl: could not start recording: <desc>"`, `recorder = nil`,
       `startedAt = nil`, `pendingFinish = nil`, `isDrawing = false`, hide overlay. No UI message.
- Duplicate `start()` while starting is ignored (exactly one recorder start).
- `stop()`/`discard()` while starting are **buffered** (`pendingFinish = discarding`) and applied on
  start success (tests: `testEarlySendIsBufferedAndDuplicateStartDoesNotReplaceRecorder`,
  `testEarlyDiscardIsBuffered`).
- Touches and drawing are accepted during `isStarting` (`testOverlayDrawsBeforeAudioStart…`).

### 6.2 Finish (`finish(discarding:)`)

1. If starting → buffer, return. Require recording + `startedAt` + recorder.
2. `recorder = nil`, `isRecording = false`, `isDrawing = false`, cancel limit task, clear notice.
3. Discard → begin Undo window (§7.6); otherwise `uploadStatus = sending`.
4. Directory: hosted → staging `tmp/ScrawlStaging/`; else `Documents/Scrawl/` (created).
5. File stem = `startedAt` formatted as ISO-8601 **UTC** date + `T` + time without separators,
   no fractional seconds, no zone: **`yyyy-MM-dd'T'HHmmss`**, e.g. `2025-10-13T164000` (verified).
   Files: `<stem>.mp4`, `<stem>.json`, `<stem>.ready`, `<stem>.sent`, `<stem>.delivery`.
6. Build the timeline dict (§7) **now** (duration measured now), merge
   `ScrawlAppContext.end()` (existing keys win), set `startedAt = nil`, clear `completedStrokes`,
   clear ink (animated).
7. `recorder.stop(writingTo: movie)`; on completion (main):
   - error → log `"Scrawl: recording failed: <desc>"`, `uploadStatus = idle`, end pending discard
     if it was this one, hide overlay after exit. (No failure bubble.)
   - success → `info["audio"] = recorder.recordsAudio`; apply `timelineSupplement` (overwrites);
     serialize JSON (pretty-printed, **sorted keys**); write `<stem>.json`; then:
     not a discard → `deliver`; discard undone meanwhile → `deliver`; discard pending with this id →
     hold as `discarded`; else delete all its files.

### 6.3 Recorder protocol

```
interface ScrawlRecorder {
  var startupTrace; val method; val isAvailable: Boolean
  val recordsAudio: Boolean        // read after stop
  var onInterruption: (() -> Unit)? // app left foreground: stop as if Send was tapped
  fun start(microphone: Boolean, completion: (Result<Date /* movie t=0 */>) -> Unit)
  fun stop(writingTo: File, completion: (Throwable?) -> Unit) // replaces any existing file
  fun cancel()
}
```
Invariant: **movie time 0 = timeline t = 0 = `startedAt`**.

### 6.4 App-frame recorder (`appFrames`, default)

Constants (exact):
- `pixelsPerPoint = 1.5`, `maxLongEdge = 1600`, `framesPerSecond = 30`.
- `videoBitRate = 6_000_000` (LAN/Mac), `hostedVideoBitRate = 3_000_000` (HTTPS),
  `audioBitRate = 96_000`.
- Canvas: `scale = min(1.5, 1600 / max(w,h))` (1.5 if zero), each dimension
  `max(2, round(v*scale/2)*2)` (even). E.g. 440×956 pt → **660×1434**; 1024×1366 → 1200×1600.
  (README says 2× / 880×1912; **code is 1.5×**.) Android: same formula on the activity window size
  in dp; consider rounding to a multiple of 16 if an encoder rejects the size (fallback, test on
  devices).
- Video: H.264, High profile auto level, average bitrate as above, expected FPS 30, max keyframe
  interval 30 frames **and** 1.0 s, **no frame reordering (no B-frames)**, BT.709 primaries /
  transfer / matrix, `expectsMediaDataInRealTime`, MP4 container, not network-optimized.
  Android: `MediaCodec` `video/avc`, `KEY_PROFILE=AVCProfileHigh` (fallback Baseline/Main),
  `KEY_BIT_RATE`, `KEY_FRAME_RATE=30`, `KEY_I_FRAME_INTERVAL=1`, `KEY_MAX_B_FRAMES=0` (API 29+),
  `KEY_COLOR_STANDARD=BT709`, `KEY_COLOR_TRANSFER=SDR_VIDEO`, `KEY_COLOR_RANGE=LIMITED`, input
  Surface; `MediaMuxer` MPEG_4.
- Audio: AAC-LC, **mono, 48 000 Hz, 96 kbps**.
- Start: `startedAt = Date()` and `start = hostClock.now` at start invocation; the **first frame is
  stamped at `start`**, later frames at now. Writer session starts at `start`. So the first-frame
  Date returned = start-invocation Date.
- Startup runs writer creation off-main (`writerBegin`/`writerEnd`), microphone in parallel
  (`permissionBegin`/`permissionEnd`, `audioSessionBegin/End`, `engineBegin/End`,
  `microphoneReady` or `microphoneUnavailable`). Completion fires only when **writer ready AND
  audio ready (or skipped/denied) AND first frame captured** (`firstCapture`).
- Microphone permission: granted → start mic; undetermined → request, then start or skip; denied →
  video only. `recordsAudio = audioSampleCount > 0` after finish. Android: `RECORD_AUDIO` runtime
  permission via the foreground Activity (`ActivityResultRegistry`); denied → video only.
- Display link: preferred 30 fps (min 15). Each tick: feed `lastTouchMoveMediaTime` into the pacer,
  compute `interacting`, `writerReady`, `pacer.decide(...)`; on capture draw and
  `pacer.recordCapture(...)`. Android: `Choreographer.FrameCallback`; `linkTimestamp` =
  `frameTimeNanos`, `targetTimestamp` = frameTime + vsync period (API 33 `FrameData` or
  `1/display.refreshRate`), `now` = `System.nanoTime()`.
- Drawing a frame: all visible app windows back-to-front (sorted by window level, then order),
  **excluding ScrawlKit's overlay**, the system keyboard window, hidden/alpha ≤ 0.01/empty windows,
  and empty text-effects windows (kept only when they hold visible controls/labels/images/effect
  views or non-container leaf content, depth ≤ 6). Fill black first; fit the scene into the canvas
  preserving aspect, centered (only differs after rotation); top-left origin; low interpolation.
  Then draw the **keyboard panel** over the keyboard's current (animated) frame (§6.7).
  [A] Android: enumerate the app's windows (Activity window, Dialogs, PopupWindows, Toast-less):
  no public API — use `WindowManagerGlobal` reflection or the `square/curtains` library; draw each
  root view at its on-screen location (`getLocationOnScreen`) into
  `inputSurface.lockHardwareCanvas()` via `View.draw(canvas)` (RenderNode-backed, fast) with a
  `PixelCopy` fallback for windows that don't draw (SurfaceView/TextureView/video/camera/GL content
  will be blank — same documented caveat as iOS).
- Writer: one frame in flight; frames must have strictly increasing PTS; a spare pixel buffer is
  pre-allocated off-main. Frames accepted before finish are still appended. Audio before the first
  frame is dropped.
- Finish at `end = hostClock.now`: if no frame was ever written → error `"no frames were recorded"`.
  The **last frame is repeated at `end`** so the movie is as long as the timeline; end session at
  `max(end, lastVideoTime)`; move temp file (`tmp/scrawl-<UUID>.mp4`) to the destination. Runs
  under a background task (iOS) so it completes even if backgrounded. Android: MediaMuxer finish is
  quick; run on a background executor not tied to the Activity; re-render the last frame at `end`
  (redraw to the input surface with `setPresentationTime`/`eglPresentationTimeANDROID`, or keep the
  last bitmap).
- Backgrounding: on app entering background while starting → fail start; while recording →
  `onInterruption` → `Scrawl.stop()` (sends). Brief foreground interruptions keep recording; the
  mic restarts after an interruption ends. Android: `ProcessLifecycleOwner` `ON_STOP` → interrupt.
- Errors: `"No active window scene to record"`, `"Not recording"`,
  `"Could not write the movie: <reason>"`.
- `lastMetrics`/signposts (`CaptureSummary`, `CaptureSkip`, `CaptureDraw`, `VideoAppend`) are
  diagnostics; Android: `android.os.Trace` sections with the same names (optional).

**Microphone pipeline** (iOS AVAudioEngine; Android `AudioRecord` + `MediaCodec` AAC):
- iOS audio session: category playAndRecord, options mixWithOthers + defaultToSpeaker +
  allowBluetoothA2DP; restored afterwards (deactivated if the app's original category wasn't
  playback/playAndRecord/record/multiRoute). Android: do **not** request audio focus (= mix with
  others); `AudioSource.MIC` (or `VOICE_RECOGNITION`), 48 kHz mono PCM16.
- Every buffer is converted to 48 kHz mono (downmix) so route changes never change the encoder
  format.
- Timestamps on the same monotonic clock as video. Contiguous timestamps from the first buffer;
  resync to the buffer's own time only after a gap > **50 ms**; drop buffers more than 50 ms early.
  Android: `AudioRecord.getTimestamp(ts, TIMEBASE_MONOTONIC)` to map frames → nanoTime.
- Up to **6** buffers (~0.5 s) are queued while the writer isn't ready.
- Restart on route/config change, interruption end, media-services reset (iOS). Android: restart on
  `AudioRecord` errors / device change (`AudioRecord.OnRoutingChangedListener`).
- Purpose string: `NSMicrophoneUsageDescription` required in the host Info.plist. Android:
  `RECORD_AUDIO` permission in the library manifest; host provides rationale if desired.

### 6.5 FramePacer (port 1:1; pure, time passed in seconds)

Constants: `defaultIdleInterval = 1/30`, `defaultInteractionInterval = 1/12`,
`defaultMaxAdaptiveInterval = 1/10`, `defaultMaxStarvationInterval = 0.14`,
`defaultMinSettledInterval = 1/60`, `defaultSettleDelay = 0.22`, `phaseTolerance = 0.004`,
`proMotionBudget = 1/120`, `sixtyHzBudget = 1/60`.

Logic (exactly as `FramePacer.swift`):
- `noteInteractionMovement(at)` sets `lastInteractionTime`.
- `isInteractionActive(explicit, at now)` = explicit || (now ≥ lastInteraction && now −
  lastInteraction < settleDelay).
- `targetInterval(interacting)` = base (interaction or idle); with a smoothed draw cost `d > 0`:
  `min(max(base, d * (interacting ? 8 : 4)), maxAdaptiveInterval)`.
- `decide(linkTimestamp, targetTimestamp, now, isInteracting, writerReady)`:
  1. `!writerReady` → skip(writerBusy) (+metric).
  2. active = isInteractionActive. No previous capture → capture(first).
  3. If !active && lastCaptureWasInteracting → needsSettledFrame = true.
  4. elapsed = max(0, now − lastCapture).
  5. If !active && needsSettledFrame && elapsed ≥ minSettled − tol: capture(settled) if elapsed ≥
     maxStarvation or !budgetTight, else skip(budgetTight).
  6. elapsed < target − tol → skip(cadence).
  7. elapsed ≥ maxStarvation → capture(starvationCap).
  8. budgetTight → skip(budgetTight). Else capture(cadence).
- `recordCapture(at, drawDuration, turnElapsed, isInteracting)`: update last capture, interacting
  flags, settled catch-up metric; smoothed draw = `prev*0.65 + d*0.35` (first = d); metrics; hitch
  if `turnElapsed + d` > 1/120 (and > 1/60).
- `isBudgetTight`: turnElapsed = max(0, now − link); false if ≤ 0.0018; estimated draw = smoothed ??
  0.004; tight if turnElapsed + est > 1/120, or (target > link && target − now < est).
Port all `FramePacerTests` cases (30 fps idle; 12 fps scroll + settled frame; budget skips until
0.14 s starvation cap; 10 fps floor; 0.22 s settle window; writer-busy skip; canvas sizes).

### 6.6 ReplayKit recorder (`replayKit`) [A] → MediaProjection

iOS: `RPScreenRecorder.shared()`; `isAvailable` from it; `isMicrophoneEnabled = narration`;
`startRecording` (iOS consent alert "Record screen and microphone?"); first-frame Date = completion
time; `stopRecording(withOutput:)` after deleting any existing file; `recordsAudio =
isMicrophoneEnabled`; includes overlay (strip + ink) and the real keyboard. Bitrate not settable.

Android: `MediaProjectionManager.createScreenCaptureIntent()` (consent every session; on Android 14+
offer single-app capture), a foreground service of type `mediaProjection` (Android 14+ requires the
FGS to be running before `getMediaProjection`), `VirtualDisplay` into the encoder's input Surface,
AudioRecord for mic. Unlike ReplayKit you can set bitrate: use the same 6 / 3 Mbps as appFrames at
**native** resolution (or keep 1600 px cap) and keep the hosted cap (§6.8) at 45 s for parity, or
document a change. Includes the overlay and the real IME, like ReplayKit.

### 6.7 Keyboard (`KeyboardTracker`, `KeyboardPanel`) [A]

Tracking active only while configured (installed from before any recording, so a keyboard already
up at start is known). Follows keyboard frame notifications (begin/end frame, duration); an
interrupted animation continues from the current interpolated position; interpolation uses cubic
ease-out `1 − (1 − p)^3` over the animation duration. Visible = end frame intersects the screen
with height ≥ 1. Hidden on did-hide.

Context integration (key `"keyboard"`, own token, `promote: false`):
- became visible → `"visible"`; became hidden → `"hidden"` if recording, else cleared (nil).
- `recordingWillBegin()`: if not visible, clear the key (so a down keyboard isn't a `hidden` at
  start).
- tracking turned off → clear key, reset frames.
Result in timeline: `context.keyboard = "visible"` if up at start; `contextChanges` entries
`{"key":"keyboard","value":"visible"|"hidden"}`.

Keyboard panel (app-frame recordings only; drawn over the keyboard frame, clipped to the scene):
- Light: background RGB(0.82, 0.835, 0.86); dark: white 0.17. Top separator 0.5 pt, black 8%
  (light) / white 8% (dark).
- Badge: SF Symbol `keyboard` + 6 pt + text **"Keyboard"**, system 15 pt medium; ink black 38%
  (light) / white 45% (dark); centered in the visible rect, drawn only if rect height > badge
  height + 4.
- Dark = the scene's user interface style.
Android: IME is another process's window in both cases. Track with
`WindowInsetsCompat.Type.ime()` + `WindowInsetsAnimationCompat.Callback` (gives per-frame inset
during the animation; no need to fake easing on API 30+; use the cubic ease-out fallback below).
Use a Material keyboard icon + "Keyboard".

### 6.8 HTTPS length cap
Only when hosted. Limit: appFrames **180 s**, replayKit **45 s**; warning **10 s** before. At
`limit − 10 s` (if still the same recording): notice title **"Length limit"**, detail
**"Sending in 10 s"** (no auto-dismiss), **warning** haptic; after 10 more s → `stop()` (send).
Reason: hosted payload must stay < 95 MB (≈0.39 MB/s at 3 Mbps + 96 kbps → ≈70 MB at 3:00).

### 6.9 Startup trace (opt-in)
Created only when `startupObserver != nil`. Each stage reported **once** per trace, with ms since
the trace was created (= trigger recognition) and whether on main. Stage names (keep exact):
`recognized`, `recorderCreated`, `sceneFound`, `overlayCreated`, `overlayShown`, `stripLayout`,
`firstDraw`, `outboxBegin`, `outboxEnd`, `permissionBegin`, `permissionEnd`, `writerBegin`,
`writerEnd`, `audioSessionBegin`, `audioSessionEnd`, `engineBegin`, `engineEnd`, `microphoneReady`,
`microphoneUnavailable`, `firstCapture`, `ready`. Emitted directly (not via trace): `prewarm`,
`prewarmLayout`. Never include capture content. Also emitted as signpost `ScrawlStartup`
(`stage=… ms=…`). Ordering requirement (test): the overlay's `firstDraw` happens **before** the
recorder's hardware start.

---

## 7. Timeline JSON (`<stem>.json`)

Serialized pretty-printed with **sorted keys**. All `t` values: seconds since `startedAt` (movie
t=0), rounded to 3 decimals, clamped ≥ 0 where rebased. Coordinates: **points (dp) in the touch
window**, not normalized; the Mac normalizes by `screen.width/height`.

```jsonc
{
  "app": "UpDown",                 // display name → name → bundleId → "App"
  "bundleId": "com.example.app",   // "" if none
  "version": "42",                 // CFBundleVersion (build) → Android versionCode as string
  "shortVersion": "1.4",           // CFBundleShortVersionString → versionName ("" if none)
  "device": "iPhone",              // UIDevice.name → Android: Settings.Global DEVICE_NAME ?: Build.MODEL
  "system": "iOS 18.2",            // "<systemName> <systemVersion>" → "Android 15" (Build.VERSION.RELEASE)
  "screen": { "width": 440, "height": 956, "scale": 3 },  // touch window bounds (pt) + display scale; 0s if unknown
  "recordedAt": "2026-10-13T16:40:00Z", // ISO-8601 UTC, second precision, "Z" (dedupe key on Mac!)
  "duration": 12.5,                // seconds, measured when Send/Discard was pressed
  "markers": [3.2],
  "events": [{ "t": 4.1, "label": "Done tapped" }],
  "touches": [{ "t": 4.0, "id": 1, "x": 220, "y": 900, "phase": "down", "label": "Done" }],
  "strokes": [{ "t0": 5.0, "t1": 5.8, "points": [[100.5, 200.25, 5.0], …], "fadeAt": 7.3 }],
  "capture": "appFrames",          // or "replayKit" (Android: pick a string for MediaProjection)
  "audio": true,                   // movie has microphone samples
  "session": "ses_…",              // only if sessionID non-empty (paired destination)
  "context": { "server": "studio", "keyboard": "visible" }, // values at start (strings)
  "contextChanges": [{ "t": 4.25, "key": "session", "value": "ses_2" }, { "t": 9.5, "key": "session", "value": null }],
  "screens": [{ "t": 0, "name": "Sessions" }, { "t": 12, "name": null }]
}
// + any keys returned by timelineSupplement (overwrite)
```

Details:
- `strokes[].points`: `[x, y, t]`, smoothed (never predicted) samples; `t` = the touch's own event
  timestamp converted to recording time (`touchTime + (now − startedAt) − uptimeNow`), clamped ≥ 0.
  `t0`/`t1` = first/last sample times. `fadeAt` (optional) = when its group started fading.
  Android: use `MotionEvent.getEventTime()`/historical times (uptime ms) with
  `SystemClock.uptimeMillis()` as `uptimeNow`.
- `screens`: changes during recording `{t, name|null}`; if a screen was named at start it's
  inserted first at `t: 0.0`. `contextChanges` `value` is `null` when cleared.
- Integer-valued doubles serialize as integers on iOS (`JSONSerialization`); the Mac parses both.
- Touch `t` uses the wall clock at processing; events and markers also wall clock; strokes use event
  timestamps (see above).

**What the Mac parses** (`src/phone.rs` `Timeline`, camelCase, all optional/defaulted): `app`,
`bundleId`, `session`, `recordedAt`, `version`, `device`, `system`, `screen{width,height,scale=1}`,
`markers[f64]`, `events[{t,label}]`, `touches[{t,id(i64),x,y,phase,label?}]`,
`strokes[{t0,t1,points:[[f64;3]]}]`, `shortVersion`, `context{String: any JSON}` (strings kept,
numbers/bools stringified, null dropped), `contextChanges[{t,key,value}]`, `screens[{t,name?}]`.
**Ignored by the Mac today**: `duration` (uses the movie's probed duration), `audio`, `capture`,
`fadeAt`. Unknown keys are ignored. An unparsable timeline over HTTP becomes `Timeline::default()`.

Mac interpretation: phases `down` opens, `up`/`cancel` closes, anything else appends; a touch with
travel < 12 pt and duration < 420 ms → tap (`Click`, label), else drag; drag with travel < 12 pt →
`"long press: <label>"`. Coordinates normalized by `screen.width/height` (falls back to movie pixel
size if `screen` missing — so **always send `screen`** in the same units as touches). Stroke points
normalized and time made relative to `t0`. Markers → flags. Events → `app:` lines in the summary.
`device` (default "iPhone") becomes the bundle's device name; `app` + `bundleId` the target.
Bundles are labelled `source: "iphone"`, capture `"scrawlkit"` (cosmetic Mac change desirable for
Android; not required).

---

## 8. App context and screens (`ScrawlAppContext`)

- Each screen and each context key is a **stack of setters** identified by a token (a view's UUID,
  or the static API's single `manualToken`). The most recently promoted entry still present wins.
- `set(stack, value, token, promote)`: value nil → remove that token's entry; existing entry and
  `!promote` → update in place; otherwise remove existing and append (top).
- Values cleaned: trimmed (whitespace+newlines); empty → nil. Keys trimmed; empty key ignored.
- Static `Scrawl.screen/context` use `promote: true`. View modifiers: on appear `promote: true`; on
  value change `promote: false`; on key change clear old key then set new key `promote: true`; on
  disappear set nil.
- Change logging only while `Scrawl.shared.isRecording` and after `begin(at:)`: when the effective
  value changes, append `{"t", "name"}` to screens or `{"t", "key", "value"}` to contextChanges
  (`t` = seconds since start, rounded 3 dp, ≥ 0; NSNull/`null` for cleared).
- `begin(at:)` snapshots current screen and values, clears logs. `end()` returns `shortVersion`,
  `context` (start values), `contextChanges`, `screens` (start screen inserted at t 0) and resets.
- Only what the app passes is sent (plus keyboard state).

---

## 9. Delivery

### 9.1 Reachability probe (`findMac`, at each recording start)
For every server URL in parallel: `GET <base>/health`, header `Authorization: Bearer <token>`,
timeout **4 s**. First **200** wins → `reachableURL`. A **401/403 from an https base** marks
`revoked`. After: if hosted: reachable is https → `pairingRevoked = false`; else if revoked →
`pairingRevoked = true` and, if recording/starting, notice **"Scrawl link expired"** /
**"Ask Kit for a new one"** for **5 s**. (Mac: any authorized `GET` returns `200 {"ok":true}`;
unauthorized → `401` empty body.)

### 9.2 Payload framing (LAN and HTTPS)
`[UInt32 big-endian timeline length][timeline JSON bytes][MP4 bytes]`. Content-Type
`application/octet-stream`. Mac requirements: request line starts `POST /ingest`; **Content-Length
header required** (no chunked encoding); `4 < length ≤ 250 MiB`; `4 + jsonLen < length`; bearer
token in `Authorization: Bearer …` (or `bearer `), constant-time compared; socket read timeout
**30 s** (a stalled upload fails); header timeout 5 s; max 8 concurrent connections.

### 9.3 LAN path (no https URL)
1. Write `<stem>.ready` (contents `"ready"`), append to `recordings`.
2. Build the framed payload in memory.
3. URLs: `[reachableURL]` if the probe found one, else **all** `serverURLs`. `replyWait` = **45**
   with a reachable URL, else **15**.
4. Race in parallel:
   - For each URL: `POST <base>/ingest`, headers `Content-Type: application/octet-stream`,
     `Authorization: Bearer <token>`, `X-Scrawl-Wait: <replyWait>`; request timeout
     `replyWait + 15` s. 2xx → `IngestReply(body)`; anything else/error → nil.
   - USB watcher: up to **75 × 400 ms** (30 s), check `<stem>.sent`; when it holds non-empty
     trimmed text → `IngestReply(sentMarker: text, delivery: contents of <stem>.delivery)`.
   - First non-nil wins (others cancelled). All nil → wait for all to finish (so failure appears
     after max(30 s, POST timeouts)).
5. Success → delete `.ready`, `.sent`, `.delivery` (the `.mp4`/`.json` **stay** in
   `Documents/Scrawl`) → present Sent. Failure → keep `.ready` (Mac picks it up next time cabled)
   → present failure `unreachable` with Share (share sheet only on tap).

Mac behaviour (`ingest_recording`): dedupe key = `recordedAt` (else random). Concurrent copies
(LAN + Tailscale + USB) wait for the first and get its outcome. Replies after the bundle is built
and queued, or after `min(X-Scrawl-Wait, 45 s)` (default 25 s) → `processing`. Reply body:
`{"ok":true,"delivered":bool,"state":"sent"|"queued"|"saved"|"processing","sessionTitle":"…","error"?}`;
build failure → **HTTP 500** `{"ok":false,"error":"…"}` (forgotten so it can be re-sent).
`sessionTitle` is `"Mac"` when routing is off/no session.

### 9.4 HTTPS outbox (any https URL configured)
`hostedURL` = first `https` URL. Outbox exists iff configured + hosted + non-blank token; created
lazily, torn down (session invalidated, monitor cancelled, observers removed, delivery cleared;
**files kept**) when that stops being true. Credentials (target, token) updated on each sync; a
**changed** target or token resets `pairingRevoked` and calls `retryFailed()`.

Creation also: directory `Application Support/ScrawlOutbox/`; triggers: app will-enter-foreground →
`kick(force: true)`; did-become-active → `showPendingSent()`; network path monitor: transition
unsatisfied→satisfied → `kick(force: true)`; every path change → `pathChanged`; launch →
`kick(force: true)`.

`deliverHosted(finished)`:
1. `syncOutbox()`; no outbox → failure `notSaved` (Share of staged files).
2. `enqueue(timeline, movie, target, recordedAt, failure: pairingRevoked ? .unauthorized : nil)`;
   error → log `"Scrawl: could not save the recording for sending: …"`, failure `notSaved` (staged
   files kept for Share).
3. Delete staged files (the outbox holds the only copy). Not added to `recordings`.
4. Entry already failed (revoked or too large) → present that failure (Share from entry).
5. If the strip still says Sending (no new recording took over) → `shownEntry = id`, progress nil,
   not stalled, `delivery = DeliveryTracker(entry, pathSatisfied, now)`.
6. `kick(force: true)`; `refreshDelivery()`.

**Outbox constants and rules** (port exactly; tests in `OutboxTests.swift`):
- `defaultMaxPayloadBytes = 95_000_000`. Payload larger → entry failure `tooLarge` at enqueue, never
  sent (also re-checked in `retryFailed`).
- Entry id: `<digits of recordedAt or "recording">-<first 8 lowercase hex of a UUID>`, e.g.
  `20261013164000-3fa9c2d1`.
- Files: `<id>.payload` (framed bytes, streamed in 1 MiB chunks, fsync'd) and `<id>.json`
  (metadata, atomic write). If metadata can't be written, the payload is removed and enqueue throws.
- Metadata (`Entry`, JSON): `id`, `target` (URL string), `recordedAt`, `createdAt` (iOS: seconds
  since 2001-01-01, Apple reference date — Android may use epoch ms; no cross-platform read),
  `payloadBytes` (Int64), `attempts` (Int, default 0), `lastError?`, `failure?`
  (`"unauthorized"|"tooLarge"|"rejected"`), `nextAttemptAt?`.
- Entries listed = metadata files whose payload exists, oldest `createdAt` first.
- Directory excluded from backup; files protected until first unlock (iOS). Android: put it under
  `noBackupFilesDir/ScrawlOutbox` (credential-encrypted storage = available after first unlock).
- `classify(status)`: nil → retry; 200–299 success; 401/403 → permanent `unauthorized`; 413 →
  `tooLarge`; 408/429 → retry; other 4xx → `rejected`; everything else (5xx, etc.) → retry.
- `backoff(attempts)` = `min(15 * 2^(max(attempts,1) − 1), 1800)` s → 15, 30, 60 … 1800.
- `kick(force)`: skip if invalidated; wait until the session has re-adopted surviving tasks; for
  each non-failed, not-in-flight entry: start if `force` or `nextAttemptAt ≤ now`; else schedule a
  timer for the earliest due time (min 0.5 s) that kicks with `force: false`.
- `start(entry)`: requires token; URL = `(current target ?: entry.target)/ingest`; `POST`; headers
  `Content-Type: application/octet-stream`, `Authorization: Bearer <token>`,
  **`X-Scrawl-Token: <token>`** (server also accepts it), `Content-Length: <payloadBytes>`; body =
  payload **file** (never in memory); task tagged with entry id; `attempts += 1`,
  `nextAttemptAt = nil`, saved; emit `.started(id)`.
- **Never two uploads of one entry at once** (in-flight map by entry id, including tasks carried
  over from a previous process).
- Progress: `fraction = sent/expected` clamped 0…1; emit `.progress` when the whole percent changes
  or ≥ **5 s** (`progressInterval`) since the last report.
- Response body captured up to 64 KiB.
- Finished: success → delete payload + metadata, emit `.sent(id, IngestReply(body))`. Permanent →
  set `failure`, `lastError = "HTTP <status>"`, save, emit `.failed`. Retry → `lastError` = error
  description or `"HTTP <status>"`, `nextAttemptAt = now + backoff(attempts)`, save, emit
  `.retrying(id, error, next)`, then `kick(force: false)`.
- `retryFailed()`: clear `failure`, `lastError`, `nextAttemptAt` on failed entries (re-mark
  `tooLarge` if oversize), then `kick(force: true)`.
- `remove(id)` cancels and deletes; `extract(id, folder)` writes `<id>.json` + `<id>.mp4` copies
  (for Share); `invalidate()` stops everything, files stay.
- Background session (iOS): identifier `dev.kitlangton.scrawl.outbox.<bundleId>`, not
  discretionary, sends launch events, resource timeout **24 h**.
- Server dedupes on person + `recordedAt`, so resending after a lost 2xx is safe.

[A] Android design: a `CoroutineWorker` (WorkManager) per kick, unique work
`Scrawl.backgroundSessionIdentifier`, constraint `NetworkType.CONNECTED`, run as **expedited** /
long-running with `setForeground` (FGS type `dataSync`; Android 14+ needs
`FOREGROUND_SERVICE_DATA_SYNC`) or, on API 34+, a **user-initiated data transfer job**
(`JobInfo.Builder.setUserInitiated(true)`, `RUN_USER_INITIATED_JOBS`). Upload with OkHttp using a
file `RequestBody` that reports progress (fixed Content-Length, no chunking). Keep the backoff
schedule ourselves (store `nextAttemptAt`; enqueue delayed work) rather than WorkManager's backoff,
so the 15 s…30 min schedule and `kick(force)` semantics match. In-flight dedupe: a per-entry lock
file or the unique-work name `outbox-<id>`. Events flow to `Scrawl` via a process-local
`SharedFlow` (and are recomputed from metadata on next launch).

### 9.5 Network path [A]
iOS `NWPathMonitor` (`status == .satisfied`); assumed online until told otherwise; the first update
never counts as "came back". Android: `ConnectivityManager.registerDefaultNetworkCallback`,
satisfied = has a network with `NET_CAPABILITY_INTERNET` (+ `VALIDATED` preferred);
`ACCESS_NETWORK_STATE` permission.

### 9.6 Recordings list and retention
- `recordings`: appended only for LAN-path deliveries (after `.ready` written), in memory only,
  newest last, never trimmed.
- `Documents/Scrawl/<stem>.mp4|json` are **never deleted** by ScrawlKit after a successful LAN
  delivery (only markers are). Deleted: discarded recordings (after Undo window), and
  `onRecordingSubmitted == true`.
- HTTPS recordings: staged in `tmp/ScrawlStaging`, then only in the outbox until a 2xx.
- Android: `Documents/Scrawl` → `getExternalFilesDir(null)/Scrawl` (= `/sdcard/Android/data/<pkg>/
  files/Scrawl`, readable by `adb` for the USB fallback; note Auto Backup may include external
  files dir — exclude via backup rules if the host enables backup). Staging → `cacheDir/ScrawlStaging`.

### 9.7 USB fallback (iOS) and the Android/adb equivalent [A]

iOS/Mac today (`spawn_usb_poller`, `poll_usb_app_containers`):
- Mac thread loops every **1.5 s**; needs `"usbApps": ["<bundle id>", …]` in
  `~/.config/scrawl/config.json` (GUI app always; `scrawl serve --usb`); skips unless the USB
  registry (`scrawl-helper devices`, **iPhone-only**) shows a device.
- `xcrun devicectl list devices` → first line containing `physical`, `available (paired)`,
  `iPhone` (not `unavailable`) → UDID.
- For each app and subdir `Documents/Scrawl`, `Documents/Tether`:
  `devicectl device info files --domain-type appDataContainer --domain-identifier <bundle>
  --subdirectory <subdir>`; for every `<stem>.ready` without `<stem>.sent`: copy
  `<stem>.json` then `<stem>.mp4` from the device, `ingest_recording(..., wait = 15 s)`; build
  failure → no `.sent`, retry backoff 1 min doubling to 30 min per `<bundle>/<subdir>/<stem>`;
  otherwise write `<stem>.delivery` (reply JSON) **then** `<stem>.sent` (session title text) and
  copy both **to** the device.
- Phone side: the 30 s `.sent` watch (§9.3); old Macs only write `.sent`.

Android requires **Mac changes** to get a USB path (the poller is gated on the iPhone-only helper
and uses `devicectl`). Two options:

1. **adb file poller (direct port)**: Mac detects `adb devices` (state `device`), and for each
   configured Android package: `adb -s <serial> shell ls /sdcard/Android/data/<pkg>/files/Scrawl`
   (or `run-as <pkg> ls files/Scrawl` for debuggable builds using internal storage), `adb pull`
   `<stem>.json`/`.mp4`, ingest, `adb push` `<stem>.delivery` then `<stem>.sent`. Phone side
   identical to iOS (`.ready` marker, 30 s watch). Mac changes: an adb branch in
   `spawn_usb_poller` (not gated on `helper::devices()`), Android package names in `usbApps` (the
   `safe_word` filter already allows `[A-Za-z0-9-_.@/~]`), `adb` path discovery.
2. **`adb reverse` (recommended, simpler)**: `adb reverse tcp:47777 tcp:47777` makes
   `http://127.0.0.1:47777` on the phone reach the Mac over USB; the existing HTTP ingest then works
   unchanged. Needs either the user to run it, or a small Mac change to run it when an Android
   device appears; phone side adds `http://127.0.0.1:47777` (port of the first http URL) as an extra
   candidate. This is a deviation from iOS file semantics but gives the same outcome with fewer
   moving parts. Keep the `.ready` files anyway so option 1 can be added later.

### 9.8 `IngestReply` parsing (port exactly; `IngestReplyTests`)
- From body: parse JSON object (invalid/empty → `{}`), fallback title `"Scrawl"`.
- From USB markers: `.delivery` JSON if present, fallback title = `.sent` text.
- `title` = `sessionTitle` if a non-empty string, else fallback.
- If `delivered` is missing or not a Bool, or is `true` → `.delivered(title)`.
- `delivered == false`: `state` `"queued"` → `.queued(title)`, `"processing"` →
  `.processing(title)`, anything else (incl. `"saved"`, unknown) → `.saved`.

### 9.9 `DeliveryTracker` (port exactly; `DeliveryTrackerTests`)
- `stallAfter = 30 s`. Phases: uploading, waitingToRetry, sent(reply), failed(failure). Ignores
  events for other entries and anything after finishing.
- `started` → uploading, progress nil, activity = now; `progress(f)` → uploading, progress f,
  activity = now; `retrying` → waitingToRetry; `sent`/`failed` → finished.
- `setPath(satisfied)`: unsatisfied→satisfied resets activity to now.
- `display(now)`: sent/failed as is; waitingToRetry → queued; uploading on unsatisfied path →
  queued; uploading ≥ 30 s since activity → `stillSending(progress)`; else `sending(progress)`.
- `nextChange(after)`: only while uploading on a satisfied path: `activity + 30 s` if in future.

### 9.10 Strip status logic for HTTPS (`refreshDelivery`, `handle(event)`)
- Any `.failed(_, unauthorized)` event sets `pairingRevoked = true`.
- Events for the tracked entry → apply to tracker → refresh. Others: `.sent` from an earlier
  recording → `pendingSent = reply`, `showPendingSent()`; `.failed` → present failure only if idle
  for status (app active, not recording/starting, status idle, no discard pending).
- refresh: sending/stillSending → set `sendProgress`, `isSendStalled`; if showing and status was
  queued → back to `sending` (`statusID += 1`); schedule re-check at `nextChange + 0.05 s`.
  queued → if showing and status sending → `presentQueued()`. sent → clear tracker; if showing and
  app active → present Sent; else `pendingSent` + `showPendingSent()`. failed → clear tracker;
  present failure if showing or idle.
- `showPendingSent()`: requires pendingSent, app active, not recording/starting/discard pending,
  and status idle (or a stale Sending/Queued with no tracker) → show overlay, present Sent.

### 9.11 Share-sheet fallback [A]
Share (failed bubble): clears failure, status idle, overlay leaves; if `sharesWhenDone`: LAN →
share `[movie, timeline]`; outbox entry → extract copies into `tmp/ScrawlShare`, share, delete the
copies when the sheet completes (entry stays queued). iOS presents `UIActivityViewController` from
the top-most presented VC of the key (non-overlay) window. Android: `Intent.ACTION_SEND_MULTIPLE`
with `FileProvider` content URIs (`video/mp4` + `application/json`, `FLAG_GRANT_READ_URI_PERMISSION`),
`Intent.createChooser`; library ships a `FileProvider` with its own authority
(`${applicationId}.scrawl.files`); delete copies after the chooser returns (best-effort; use
`ActivityResult` or delete on next launch).

---

## 10. Overlay, ink and the strip UI

### 10.1 Overlay window [A]
iOS: a separate `UIWindow` at level `alert + 10`, clear background, hidden by default; exists from
recording start (or prewarm) until the strip has animated away. Contains the ink view (bottom) and
the strip (SwiftUI). **Hit-testing**: touches inside any reported region (strip body or interactive
bubble) inflated by **8 pt** go to the overlay; else, if recording/starting **and** drawing, the
overlay takes the touch (ink); else it passes through to the app. Excluded from app-frame movies;
included in ReplayKit. While drawing, one finger draws; other fingers are ignored until it lifts
(and since the overlay owns them they don't reach the app). `isActivelyDrawing` = a drawing touch
exists.

Android proposal: attach an overlay `FrameLayout` (ink View + `ComposeView` strip) to the current
Activity's decor view (or a `TYPE_APPLICATION_PANEL` sub-window with the activity token), move it
on Activity change, and route touches in the wrapped `Window.Callback.dispatchTouchEvent`: regions
(+8 dp) or drawing mode → overlay; else → app (and the app touch logger). The app-frame recorder
must skip the overlay when drawing the decor view (draw the decor's other children, or temporarily
set the overlay `alpha=0` inside the draw pass). Status circle (sending/sent/…) is **not** a touch
region (touches reach the app); only the recording strip and bubbles with buttons (failed → Share,
discarded → Undo).

Presentation: `present()` unhides (alpha 1); if the strip was already laid out (prewarm), marks
`stripLayout` and on the next display frame marks `firstDraw` and runs the deferred `begin` one
main-queue turn later. `dismiss()` hides and clears. `hideOverlayAfterExit()` waits
`StripMotion.overlayLinger` (**≈ 970 ms**: exitDelay 0.22 + exitFade settling 0.70 + 0.05; verified
with SwiftUI's `Spring(duration: 0.425, bounce: 0).settlingDuration = 0.7`) then hides unless
recording/starting/status non-idle/discard pending; if a `pendingSent` exists and the app is active
it shows that instead.

### 10.2 Ink (port `Ink.swift` 1:1)
- Color **#FF3B30** (r 1.0, g 0.231, b 0.188), opaque, nonzero fill.
- `OneEuroFilter(minCutoff 3, beta 0.03, derivativeCutoff 1)`, one cutoff from 2D speed;
  `dt = max(t − prev, 1/1000)`; `alpha = 1 / (1 + 1/(2π·cutoff)/dt)`.
- `InkStrokeBuilder`: `maxWidth 4.6 pt`, `thinning 0.45` (fast = 55%), `fastSpeed 1600 pt/s`,
  `minSpacing 0.4 pt`; speed low-pass `speed += (1 − e^(−dt/0.04)) · (dist/dt − speed)`;
  `width = maxWidth · (1 − thinning · s²(3 − 2s))`, `s = clamp(speed/1600)`. Every coalesced
  sample (Android: `MotionEvent` historical points) is added; predicted points (Android:
  `androidx.input:input-motionprediction` `MotionEventPredictor`) only extend the live tip via a
  filter copy, never recorded. On lift: `finish(at: exact lift point)`.
- `InkTrace`: centered 1-2-1 / 1-2-3-2-1 smoothing (narrower at the ends, ends fixed), centripetal
  Catmull-Rom (knot = sqrt(dist), min 1e-4), extrapolated end points, `step 1.5 pt` (1…12 steps per
  segment), discs + joining quads, nonzero union. Taper `taperLength 10 pt`, `taperTip 0.35`,
  ramp `tip + (1 − tip)(1 − (1 − x)²)`; end taper only when finished. Single sample → dot radius
  `width/2 · 0.8`. Freezing: segment i final when `frozen + 4 < n` and
  `length − lengths[frozen+1] ≥ 10`; frozen points accumulate into chunk layers of **48** points;
  only the live tip is redrawn.
- Timeline: on lift, `onStroke(smoothedSamples)` → strokes entry index.
- `InkFade`: `hold 1.5 s`, `duration 0.3 s`, ease-out `(1 − p)²`; strokes drawn before the held
  group starts fading join it (pen down cancels the pending fade); groups fade together; fade
  start reported as `fadeAt`; removed after `duration + 0.05 s`. Layer fade uses ease-out timing.
- `clear(animated)`: cancel timers, reset; animated → 0.2 s fade-out then remove. Called with
  `animated: false` at start, `true` at finish.
- Times: touch timestamps (uptime). Port `InkTests` exactly (jitter reduction, straight-line
  invariants, taper radii `2 · 0.35`, tap is a dot, fade grouping).

### 10.3 Strip layout and controls
Metrics: strip `width 42`, hit/row `44`, dot row `42`, divider `9`,
`expandedHeight = 42 + 3·44 + 9 = 183`; `dragThreshold 8`; row centers relative to strip center:
dot, draw, divider, discard, send. Bubble: hidden droplet `28`, hidden gap `−35`, settled gap `8`,
glass spacing `7`, neck reach `18`, glass tint black **45%**.

Rows (top→bottom):
1. **Recording dot** (18×18): ring white 16% lw 1.25; progress ring white 75% lw 1.25 round cap,
   from −90°, fills once per **60 s** (updates every 1 s); red dot 8×8 pulsing scale 1↔0.78,
   opacity 1↔0.55, ease-in-out 1 s (static with Reduce Motion). A11y label
   `"Recording, <m> minute|minutes <s> second|seconds"`, hint
   `"Hold to show the time. Drag to move the controls."`. Gesture: press shows the elapsed bubble
   (`m:ss`, monospaced digits, e.g. `0:42`, `75:00`); moving > 8 pt drags the strip.
2. **Draw**: icon `pencil.tip` 17 pt medium in 32×32; off white 85%; on Scrawl red with red 22%
   circle; a11y label `"Draw"`, value `"On"`/`"Off"`.
3. Divider: 18 × hairline (1 px) white 20%.
4. **Discard**: `xmark`, a11y `"Discard recording"`.
5. **Send**: `arrow.up` 16 pt semibold white on a 32×32 Scrawl-red circle, a11y
   `"Send recording"`; tap → light haptic → `stop()`.
Button press style: scale 0.86, opacity 0.7, snappy 0.15 s. Android icons: Material
equivalents (edit/pen, close, arrow_upward, check, outbox/upload, warning, delete).

Status icon (42×42, at the body center, not touchable):
- sending: with progress → 22×22 ring, track white 22% lw 2.5, progress white round cap from −90°,
  min 0.02, animated ease-out 0.3 s; a11y `"Sending recording"`, value `"NN%"`. Without progress →
  small white spinner, a11y `"Sending recording"`.
- sent: `checkmark` 17 semibold **green**, a11y `"Sent"`.
- queued: `tray.and.arrow.up` 16 medium white 85%, a11y `"Recording queued"`.
- failed: `exclamationmark.triangle.fill` 16 medium **yellow**, a11y `"Not sent"`.
- discarded: `trash` 16 medium white 60%, a11y `"Recording discarded"`.

Bubbles (footnote semibold ≈13 sp, white, one line, padding h12 v8; two-line notices: title +
detail in caption medium ≈12 sp white 75%, spacing 1):
- elapsed: `m:ss`.
- sending: `"Sending…"` / `"Still sending…"` (after 30 s stall) — appears only after **1.2 s**
  (`sendingBubbleDelay`), so fast uploads go straight to Sent.
- sent: delivered `"Sent to <title≤36…>"`; queued `"On your Mac"` + `"Sends to <title≤24…> when it
  can"`; processing `"On your Mac"` + `"Sending to <title≤30…>…"`; saved `"Saved on your Mac"`.
  (Shortening: > n chars → first n + `"…"`.)
- queued: `"Queued · sends when online"`.
- failed (notice + **Share** button: label `"Share"` + share icon, padding h9 v4, white 18% capsule):
  `unreachable` → `"Mac didn't answer"`; `linkExpired` → `"Scrawl link expired"` / `"Ask Kit for a
  new one"`; `tooLarge` → `"Too large to send"`; `rejected` → `"Couldn't send"` / `"The server
  rejected it"`; `notSaved` → `"Couldn't save to send"`. Outbox failure mapping: unauthorized →
  linkExpired, tooLarge → tooLarge, rejected → rejected.
- discarded: `"Discarded"` (white 75%) `" · "` (white 40%, a11y hidden) `"Undo"` (white; a11y
  `"Undo discard"`; hit inset −10).
- notice (while recording only): link expired, length limit (`"Length limit"` / `"Sending in 10
  s"`). Holding the dot shows the elapsed time over a notice.
Only failed and discarded bubbles are touchable.

Glass [A]: iOS 26 Liquid Glass capsules (regular, tinted black 45%) in a container that blends body
and bubble into one shape when < 7 pt apart; pre-26: one path = body capsule ∪ bubble capsule ∪
metaball "neck" (quad-curve bridge between caps at angle 0.95 rad, half-width
`min(r)·sin(0.95)·(0.1 + 0.9·reach³)`, `reach = clamp(1 − gap/18)`, only when
`−bubbleHeight < gap < 18`), filled with ultra-thin material (dark) + black 45% + white 22% hairline.
Android: implement the pre-26 path (Compose `Path` ops union) with a dark translucent fill
(black ~60–70%) and optional `RenderEffect.createBlurEffect` backdrop on API 31+.

### 10.4 Placement and drag
- Persisted per app (UserDefaults → SharedPreferences): `"dev.kitlangton.scrawl.strip.side"` =
  `"leading"`|`"trailing"` (default **trailing**; leading = **left**, trailing = **right**,
  physical, not RTL-mirrored) and `"dev.kitlangton.scrawl.strip.position"` = Double 0…1 (default
  **0.5**; 0 top of the movable range, 1 bottom).
- Movable area for the strip center: inside the safe area, **above the keyboard** (keyboard top
  from frame-change notifications), `6 pt` from side edges, `8 pt` from top/bottom; x range
  `[6 + 21, width − 6 − 21]`, y range `[8 + 91.5, height − keyboardOverlap − 8 − 91.5]`.
- Drag: once translation > 8 pt → dragging, light haptic; strip follows `clamp(origin + translation)`
  exactly, keeping velocity for stretch. Release: landing = dropped + velocity·0.3 (x) and
  velocity·0.3·0.25 (y); side = landingX < width/2 ? leading : trailing; position from landingY;
  persist; release with velocity; rest at the new spot animated; **soft** haptic. A system-cancelled
  gesture puts the strip back at its stored spot.

### 10.5 Strip motion model (`LiquidStrip`, port the spring set)
Hand-run springs stepped by a display link only while something moves (stop when all settled, no
timers, no fade). `dt` = frame delta clamped to [0, 1/20] (first frame 1/60), substeps of ≤ 4 ms;
settle when |v| < 0.01 and |x − target| < 0.001. Springs are SwiftUI `Spring(duration, bounce)`:
mass 1, stiffness `(2π/duration)²`, damping `4π(1 − bounce)/duration`.

| Name | duration, bounce |
| --- | --- |
| change | 0.42, 0.18 |
| quick | 0.21, 0 |
| exit | 0.34, 0 |
| exitFade | 0.425, 0 |
| stretchLag | 0.4704, 0.18 |
| pourLag | 0.483, 0.18 |
| unsquash | 0.462, 0.38 |
| throwStretch | 0.231, 0.35 |
| shake | 0.189, 0.55 |
| rowIn / rowGather / rowOut | 0.336 / 0.294 / 0.168, 0 |
| iconIn / iconPop | 0.294, 0 / 0.336, 0.18 |
| bubbleBud | 0.441, 0.38 |
| bubbleWidth / bubbleHeight | 0.42 / 0.378, 0.18 |
| bubbleTextIn / bubbleTextOut / bubbleAbsorb | 0.525 / 0.147 / 0.378, 0 |

Timings: stagger 0.045, fanDelay 0.04, iconSwapDelay 0.12, bubbleDelayAfterPour 0.3, bubbleDelay
0.18, sendingBubbleDelay 1.2, shakeDelay 0.06, shakeVelocity 900 pt/s, gulpVelocity 1.4 (scale
kick), exitDelay 0.22, stretchSpeed 2600 pt/s, maxStretch 0.3, reducedFade 0.11 (ease-in-out quad),
glass presence fade 0.2 s.

Spring keys and initial values: alpha 0, slide 16, scale 1, top −21, bottom 21, squashX/Y 1,
throwX/Y 1, shake 0, gather 0, iconAlpha 0, iconScale 1, row0…4 0, bubbleWidth/Height 28,
bubbleGap −35, bubbleAlpha 0, placeX/Y (rest point).

Transitions (variant A, see `site/public/prototype/strip/index.html` for the reference
animation):
- **→ recording** (`toStrip`): fresh (no previous or alpha < 0.3): seed slide 18, alpha 0.15, scale
  0.9, top −21, bottom 21, squash 1.2/0.85, gather 1, rows 0, icon hidden, glass present; else
  gather from current center and fade the icon (quick). Then alpha→1 (quick), slide→0, scale→1
  (change), squash→1 (unsquash), top→−91.5 (change), bottom→+91.5 (stretchLag), gather→0 (change),
  rows fan in from the middle: delay `0.04 + ring·0.045` with ring `[2,1,0,1,2]` → row opacity 1
  (rowIn). Show the notice bubble after 0.3 s if one exists.
- **→ status** (`toStatus`): target center = discard row (if discarded) or send row when coming
  from the strip; else current center if visible, else send row. Hide bubble; if from nothing,
  rows 0 + present. alpha→1, slide→0, scale→1; top→target−21 (change), bottom→target+21 (pourLag).
  From strip: gather into target (rowGather), rows→0 (rowOut), icon swap after 0.12 s; else swap now
  with a gulp (+1.4 scale velocity) if a status was already shown. Icon: alpha 0→1 (iconIn), scale
  0.5→1 (iconPop). Bubble after 0.3 s (from strip) or 0.18 s; sending bubble after 1.2 s. Failed →
  after 0.06 s shake velocity `−direction · 900`.
- **→ gone** (`toGone`): hide bubble; wait 0 (from recording) or 0.22 s; after discarded: scale→0.35,
  alpha→0 (exit), icon→0 (rowOut); otherwise slide→90, squash 1.2/0.86 (exit), alpha→0 (exitFade),
  icon→0 (exit). Glass dematerializes once alpha < 0.3 while exiting.
- Bubble show: active, kind, y = dot row (elapsed/notice) or body center; gap→8 (bubbleBud),
  size→measured (bubbleWidth/Height), alpha→1 (bubbleTextIn). Hide: alpha→0 (bubbleTextOut),
  gap→−35, size→28 (bubbleAbsorb); `now` sets them instantly.
- Stretch while recording (not exiting, not reduce motion):
  `sx = min(|vx|/2600, 0.3)`, `sy = min(|vy|/2600, 0.3)`, throwX→`1 + sx − 0.45·sy`,
  throwY→`1 + sy − 0.45·sx` (throwStretch).
- Geometry: `ax = placeX + dir·(slide + shake)` (dir +1 right, −1 left); width = 42·squashX·throwX
  ·scale; height = max(0, (bottom − top)·squashY·throwY·scale); body centered at
  `(ax, placeY + (top+bottom)/2)`; row opacity = row·alpha, offset = gather·(gatherY −
  rowCenter)·0.7, scale = 1 − 0.35·gather; controls live only while recording; bubble sits at the
  inner edge minus gap; bubble interactive when active, alpha > 0.5 and kind is failed/discarded;
  rows touchable only when opacity > 0.3.
- Reduce Motion (Android: `Settings.Global.ANIMATOR_DURATION_SCALE == 0` or
  `AccessibilityManager` remove-animations): fade the whole strip out (0.11 s), apply the new state
  instantly (run all pending timers), fade in.

### 10.6 Strip mode precedence (`OverlayView.stripMode`)
1. recording or starting (with `startedAt`) → `recording(startedAt, isDrawing)`.
2. prepared (`preparedAt`) → `recording(preparedAt, false)` (alpha 0 window).
3. discard pending → `discarded`.
4. `uploadStatus`: idle → none; sending → `sending(progress, stalled)`; sent → `sent(reply)`;
   queued → `queued`; failed → `failed(deliveryFailure)`.
Notice shown only while `isRecording`.

---

## 11. Status timings and haptics

| Moment | Duration | Haptic (iOS) | Android suggestion |
| --- | --- | --- | --- |
| Setup link applied | – | notification success | `HapticFeedbackConstants.CONFIRM` (API 30+) |
| Reset link | – | notification warning | `REJECT` |
| Trigger recognized (start or send) | – | notification success | `CONFIRM` |
| Draw toggle / Discard / Undo / Send button | – | impact light | `VIRTUAL_KEY` / `CLOCK_TICK` |
| `mark()` | – | impact medium | `CONTEXT_CLICK` |
| Touch held still 420 ms (< 12 pt) | – | impact rigid | `CLOCK_TICK` or `SEGMENT_TICK` (API 34) |
| Strip drag starts / lands | – | light / soft | `VIRTUAL_KEY` / `GESTURE_END` |
| Length-limit warning | until send | notification warning | `REJECT` |
| Sent (delivered) | **2.2 s** | success | `CONFIRM` |
| Sent (queued/processing/saved, two-line) | **3.5 s** | success | `CONFIRM` |
| Queued | **3.5 s** | success | `CONFIRM` |
| Failed | **8 s** | warning | `REJECT` |
| Discarded (Undo window) | **5 s** | (light from discard) | |
| Link-expired notice while recording | **5 s** | – | |
| Prewarm expiry | 2 s | – | |
| Overlay linger after strip leaves | ≈ 0.97 s | – | |

Haptics must be performed from a View on Android (`view.performHapticFeedback`); fall back to
`Vibrator` effects (`EFFECT_CLICK`, `EFFECT_TICK`, `EFFECT_HEAVY_CLICK`) on old APIs. Status
presentations use a monotonically increasing `statusID` so a later status cancels the earlier
timer.

`presentSent(reply)`: clears `pendingSent`, status `sent`, success haptic, after 2.2/3.5 s → idle +
overlay exit (if still current). `presentQueued()`: status queued, success haptic, after 3.5 s →
idle, `shownEntry = nil`, exit. `presentFailure(f, recording?, entry?)`: stores failure + share
target, shows overlay, status failed, warning haptic, after 8 s → idle, clear share targets, exit.

### 11.1 Discard and Undo (§6.2)
`discard()` → `beginUndoWindow()`: `discardID += 1`, `isDiscardPending = true`; after **5 s** if
still pending with this id → finalize (delete `.mp4`, `.json`, `.ready`, `.sent`, `.delivery`) and
exit. `undoDiscard()`: pending → not pending, status `sending`, light haptic; if the movie is
already written → deliver now; else remember `undoneDiscardID` so it's delivered when written.
Starting a new recording finalizes a pending discard immediately.

---

## 12. Platform requirements

iOS (for reference): `NSMicrophoneUsageDescription`; host URL scheme (`CFBundleURLTypes`); local
network prompt (host should provide `NSLocalNetworkUsageDescription`); optional app-delegate
background URLSession forwarding.

Android (library manifest unless noted):
- `INTERNET`, `ACCESS_NETWORK_STATE`, `RECORD_AUDIO`.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` (screen capture),
  `FOREGROUND_SERVICE_MICROPHONE` (if recording continues briefly while backgrounding — iOS stops on
  background, so likely not needed), `FOREGROUND_SERVICE_DATA_SYNC` or `RUN_USER_INITIATED_JOBS`
  (uploads), `POST_NOTIFICATIONS` (FGS notification on 13+).
- A `FileProvider` for Share.
- **Cleartext HTTP to LAN/Tailscale IPs** (`http://100.x…:47777`): blocked by default on API 28+.
  The host must allow it (`android:usesCleartextTraffic="true"` in its debug manifest, or a debug
  `network_security_config` with `cleartextTrafficPermitted`; IP ranges can't be wildcarded).
  Document prominently; consider a debug-only manifest overlay shipped by the library.
- Host: deep-link intent filter `<data android:scheme="<scheme>" android:host="scrawl"/>` (+
  `tether`), `android:launchMode` handling (`onNewIntent`).
- Local network access restrictions on newer Android (16+ opt-in "local network protection") may
  require a runtime permission in future; track.
- Min SDK proposal: 26 (PixelCopy, `lockHardwareCanvas` 23, MediaCodec/Muxer); 29 for
  `KEY_MAX_B_FRAMES`; 30 for IME insets animation and `CONFIRM`/`REJECT` haptics.

---

## 13. Feature checklist

1. `ScrawlConfig` value type with trimming/normalization and `isValid`.
2. `parseHTTPBaseURL`: trim, strip trailing slashes, http/https only, host required.
3. `validBaseURLs`: normalize, drop invalid, dedupe preserving order.
4. Setup URL parsing: any scheme, host `scrawl` or `tether`, repeated `url`, `token`, `session`.
5. Reset link `reset=1|true` (case-insensitive) wins over other params.
6. Invalid setup link (no URL or token) returns false and changes nothing.
7. `handleSetupURL` saves config, applies it with overrides, success haptic.
8. Reset deletes stored config, applies resolved (overrides only), warning haptic.
9. 250 ms duplicate-link suppression of haptics.
10. Encrypted, no-backup persistent store for config JSON (`urls`, `token`, `sessionID`).
11. Launch overrides (`scrawl-url` multi + comma split, `scrawl-token`, `scrawl-session`) via intent extras, same merge rules.
12. `isConfigured` semantics (non-empty URLs + non-blank token).
13. Configuration-changed notification posted on next main-loop turn; installers resync.
14. Losing configuration mid-recording cancels without sending and hides overlay.
15. `setTeamEndpoint(url, token)` in-memory override; nil restores stored config.
16. Complete inertness when unpaired (no observer, recognizer, overlay, keyboard tracking, network, files, outbox).
17. `Scrawl.install`/`ScrawlHost` with `trigger` and `isEnabled`; non-consuming deep-link handling.
18. Non-consuming touch observer on every app window (Window.Callback wrapper).
19. Three-finger long press: exactly 3 pointers, 500 ms, 10 dp allowable movement, fires once.
20. Trigger: success haptic; starts, or sends if recording/starting.
21. `ScrawlTrigger.none` (manual start/stop).
22. Prewarm overlay on third finger down; transparent layout; 2 s expiry; `prewarm`/`prewarmLayout` events.
23. `suppressesSystemEditingGestures` property kept (documented no-op on Android).
24. Public `toggle`, `start`, `stop`, `discard`, `mark`, `toggleDrawing`, `prepareToStart`.
25. `Scrawl.event(label)` timeline events.
26. Observable state: `isRecording`, `isStarting`, `isDrawing`, `uploadStatus`, `startedAt`, `markers`, `completedStrokes`, `recordings`.
27. `narration`, `captureMethod`, `sharesWhenDone` options with iOS defaults.
28. `timelineSupplement` hook (keys overwrite).
29. `onRecordingSubmitted` hook (true → delete files, no upload).
30. `startupObserver` with exact stage names, once per trace, main-thread flag.
31. Start sequence: overlay shown first, hardware started after first presentation.
32. Duplicate start ignored; Send/Discard during start buffered.
33. Touches/ink accepted while starting; early input rebased to the movie clock.
34. Start failure: silent log, overlay hidden.
35. Recorder unavailable: silent abort.
36. Reachability probe `GET /health` with 4 s timeout at each start.
37. App-frame recorder: draws app windows only (overlay excluded), black fill, aspect fit.
38. Canvas 1.5 px/dp capped at 1600 px long edge, even dimensions.
39. H.264 High, 6 Mbps (Mac) / 3 Mbps (HTTPS), 30 fps, 1 s keyframes, no B-frames, BT.709, MP4.
40. AAC mono 48 kHz 96 kbps microphone track on the same monotonic clock.
41. Mic permission flow; denied → video only; `audio` flag from actual samples.
42. Audio resampling to 48 k mono, 50 ms resync/drop tolerance, 6-buffer pre-writer queue.
43. No audio focus request (mix with other audio); restart on routing/errors.
44. First frame stamped at start instant; movie t=0 = `startedAt`.
45. Last frame repeated at stop time so movie length matches timeline.
46. "no frames were recorded" error path; temp file moved atomically.
47. Background → stop and send (process lifecycle ON_STOP).
48. FramePacer ported 1:1 with all constants and tests.
49. Choreographer-driven tick with interaction detection (touch moves, ink, scroll state).
50. Multi-window capture (dialogs/popups) in z-order.
51. Keyboard tracker (IME insets, animated frame, cubic ease-out fallback).
52. Keyboard panel stand-in ("Keyboard" badge, light/dark colors, separator).
53. `keyboard` context value: visible / hidden, cleared at start when down.
54. MediaProjection recorder (consent, FGS type mediaProjection, includes overlay + IME).
55. `capture` value in timeline per method.
56. HTTPS length cap: 180 s app frames / 45 s screen capture, 10 s warning notice + warning haptic, auto-send.
57. Touch IDs: sequential from 1 per recording; pre-existing fingers ignored; reset on all-up.
58. Touch phases `down/move/up/cancel`; window-relative dp coordinates, 3-dp rounding.
59. Move throttle 1/30 s per touch; wall-clock `t`.
60. Hit label lookup (a11y label, button title, text, nearest child label ≤110 dp, 7 levels, depth 3).
61. Compose semantics label lookup.
62. Label cleaning: whitespace collapse, 48 chars + "…".
63. Hold tick: rigid haptic after 420 ms within 12 dp.
64. Touches on overlay/strip/ink not logged.
65. Timeline file: all keys per §7, pretty + sorted keys.
66. `recordedAt` ISO-8601 UTC second precision with `Z`.
67. File stem `yyyy-MM-dd'T'HHmmss` (UTC).
68. `app`, `bundleId`, `version` (versionCode), `shortVersion` (versionName), `device`, `system`, `screen{width,height,scale}`.
69. `duration` measured at Send/Discard.
70. `session` key only when sessionID non-empty.
71. Screen naming: `Scrawl.screen`, `ScrawlScreen` composable/View helper with token stacks.
72. Context values: `Scrawl.context`, `ScrawlContext` composable with promote/update/remove semantics.
73. `context`, `contextChanges` (null on clear), `screens` (start screen at t 0) in the timeline.
74. Ink overlay: drawing mode takes all touches; one finger draws; others ignored.
75. Coalesced (historical) samples + predicted tip (motion prediction), predictions never recorded.
76. One-euro filter (3, 0.03, 1) and speed-based width (4.6 dp, 55% fast, 1600 dp/s).
77. Centered smoothing + centripetal Catmull-Rom outline with tapers (10 dp, 0.35 tip).
78. Frozen chunk layers (48 points) so only the tip redraws.
79. Stroke fade: 1.5 s hold, 0.3 s ease-out, grouping; `fadeAt` recorded.
80. Strokes in timeline: `t0`, `t1`, `points [[x,y,t]]` on recording clock.
81. Ink cleared at start (instant) and finish (0.2 s fade).
82. Overlay host above the app with region hit-testing (+8 dp), excluded from app-frame capture.
83. Overlay follows the foreground Activity; linger ≈0.97 s after exit.
84. Strip controls: dot, Draw, divider, Discard, Send with exact sizes, icons, a11y strings.
85. Recording dot: minute ring, pulsing red dot, spoken elapsed label.
86. Hold dot → elapsed `m:ss` bubble.
87. Drag strip (8 dp threshold), throw to nearer edge, persisted side/position.
88. Strip area: safe area, 6/8 dp insets, above the keyboard.
89. Liquid spring model with all springs/timings; display-link driven only while moving.
90. Transitions: droplet → strip, pour into status circle, bubble bud/absorb, slide/shrink exit, failed shake, gulp.
91. Throw stretch from velocity.
92. Glass body + bubble with metaball neck; dark translucent tint.
93. Reduce Motion cross-fade (0.11 s).
94. Status icons: progress ring / spinner, check, queued tray, warning triangle, trash.
95. Bubble texts exactly as §10.3 (Sending…, Still sending…, Sent to …, On your Mac …, Saved on your Mac, Queued · sends when online, failures, Discarded · Undo).
96. Title shortening (36 / 24 / 30 chars + "…").
97. Notices while recording (link expired 5 s, length limit).
98. Strip mode precedence (recording > prepared > discarded > upload status).
99. Status durations: sent 2.2/3.5 s, queued 3.5 s, failed 8 s, statusID cancellation.
100. Haptics mapping per §11.
101. Discard with 5 s Undo window; Undo during write; finalize on new recording.
102. LAN delivery: `.ready` marker, framed payload, parallel POSTs, `X-Scrawl-Wait` 15/45, timeout wait+15.
103. Reachable URL used alone when the probe found one.
104. USB `.sent` watch (75 × 400 ms) reading `.delivery` reply.
105. Success cleans markers, keeps `.mp4`/`.json`; failure keeps `.ready`, shows "Mac didn't answer" + Share.
106. `recordings` list (LAN only, in memory).
107. `IngestReply` parsing incl. legacy (no `delivered`) and USB markers.
108. HTTPS: outbox only when an https URL is configured; torn down when not.
109. Outbox entry files (`.payload`, `.json`), streamed write, id format, size check 95 MB.
110. Outbox storage in no-backup, post-first-unlock storage.
111. Staging directory for hosted recordings; staged files deleted after enqueue.
112. Background upload worker surviving process death, file-based body, Content-Length.
113. Headers: `Authorization: Bearer`, `X-Scrawl-Token`, `Content-Type`, `Content-Length`.
114. Status classification (2xx / 401·403 / 413 / 408·429 / other 4xx / rest).
115. Backoff 15 s doubling to 30 min; `nextAttemptAt` persisted.
116. Kick triggers: launch, foreground, network regained, after enqueue, after retry.
117. Never two concurrent uploads of one entry; adopt in-flight work after relaunch.
118. Progress events per whole percent or every 5 s.
119. Permanent failures kept on disk with Share; `retryFailed` on new link (target/token change).
120. Revoked pairing (`/health` 401/403 on https) → notice, entry enqueued as failed `unauthorized`.
121. DeliveryTracker: Sending / Still sending (30 s) / Queued (offline or waiting) / Sent / Failed.
122. Network path monitoring (default network callback).
123. Queued → Sending when a retry starts while shown.
124. Pending "Sent to …" shown later when app returns to foreground / strip frees up.
125. Outbox failures for older entries shown only when idle.
126. Share fallback: ACTION_SEND_MULTIPLE via FileProvider; outbox extraction to temp copies, cleaned after.
127. `sharesWhenDone = false` makes Share just dismiss.
128. Stop/write failure: log, idle, overlay exit (no bubble).
129. USB path for Android (adb file poller or `adb reverse`), with the required Mac changes.
130. Cleartext HTTP configuration guidance for LAN URLs.
131. Debug-only packaging (`debugImplementation` + no-op release artifact).
132. Unit tests ported: FramePacer, Ink, DeliveryTracker, IngestReply, Outbox, startup ordering.

---

## 14. Known iOS→Android gaps / risks (summary)

These were the risks going in; each is now resolved or a documented platform limit.

1. **App-frame capture across windows** — resolved: `Windows.kt` enumerates the app's windows
   (activities, dialogs, popups) and `AppFrameRecorder` draws them under the same `FramePacer`
   budget; interaction slows capture to 12 fps (I `interactionSlowsCapture`). SurfaceViews (video,
   camera, maps), which a window only punches a hole for, are composited in with `PixelCopy`, as
   iOS's `drawHierarchy` snapshot includes such layers (I `SurfaceCaptureTest`).
2. **Touch labels in Compose** — resolved via the semantics owner (`ComposeLabels.kt`); seen live
   on the sample's Compose list.
3. **Overlay pass-through** — resolved by routing in a `Window.Callback` wrapper; the strip and ink
   stay out of the movie (I keyboard panel test decodes frames). Under TalkBack the overlay becomes a
   strip-sized touchable window (I strip accessibility nodes; D TalkBack hover).
4. **Cleartext HTTP** — documented in the README; the sample ships a debug network security config.
5. **USB fallback** — resolved on the Mac: an `adb` poller in `src/phone.rs` pulls `.ready`
   recordings and writes `.delivery`/`.sent` back (L emulator → Mac over adb; L Pixel 7 → the
   installed Scrawl.app over USB with no network route: `ingest adb …: replied Done(Saved)`).
6. **MediaProjection consent** — inherent to Android: consent each recording plus an FGS. That's
   why app frames, which need no consent, are the default (L both methods).
7. **Background upload limits** — resolved with the durable outbox and WorkManager
   (`OutboxWorker`); L app backgrounded mid-send, finished later.
8. **Three-finger gestures** — parity: iOS's switch turns off the *app's* three-finger editing
   gestures; Android apps have none, so the hold reaches the trigger unaided. System-wide gestures
   (iOS Zoom, some Android phones' three-finger screenshot) sit outside any app on both platforms.
   The trigger is verified with real multi-touch input (I through the input dispatcher; L `adb emu`
   multi-touch events with pressure/touch-major, prewarm → recognised ≈0.5 s → ready, second hold
   sent).
9. **MediaCodec quirks** — handled: even dimensions, explicit VBR/GOP/BT.709/no B-frames, and the
   encoder chosen like AVAssetWriter's: a hardware encoder with High profile wherever the device has
   one (U `EncoderChoiceTest`), High requested only where advertised (an emulator's software encoder
   accepts the key and silently writes Baseline; I `theMovieIsHighProfileH264…` checks the SPS).
   Hardware canvases reach the encoder asynchronously, so frames carry their capture time (not the
   buffer's queue time) and the stream ends only after the stop frame is encoded.
10. **`recordedAt` second precision is the Mac's dedupe key** — kept identical to iOS on purpose.
11. **Liquid Glass** — resolved: on Android 13+ the strip is drawn as iOS 26 draws it
    (`glassEffect(.regular.tint(.black.opacity(0.45)))` in a container): an AGSL pass
    (`LiquidGlass.kt`) over the GPU-blurred app behind it merges body and bubble with a smooth
    union, bends the backdrop along the edge normal, adds slight edge dispersion, a top-left
    specular rim and the 0.45 tint (I `theStripIsLiquidGlassWithALitRim`; D lensing). Android 12
    gets iOS's pre-26 material (blur + tint + hairline), as iOS before 26 does, and Android 8–11 get
    the same material blurred on the CPU from a 1/6-size copy of the app (`SoftwareBlur.kt`). The motion model is
    a port of `LiquidStrip` (U `LiquidStripTest`).

## 15. Android status

All 132 items are implemented in `scrawlkit/`. The instrumented suite runs on a Pixel 7 (Android 17, hardware encoder: 28/28, none skipped), Android 16 (API 36 emulator: 28/28) and Android 9 (API 28: 22 pass; the 6 movie tests skip there because that emulator image's only H.264 encoder aborts on surface input, as Android's own `screenrecord` does). Android lint reports no NewApi errors from minSdk 26: running on Android 9 found and fixed three calls that crashed below their API level (`MediaFormat.removeKey` 29, `MotionPredictor` 34, `InputStream.readNBytes` 33). Evidence: **U** unit test (`testDebugUnitTest`, 69 tests), **I** instrumented test on an emulator (`connectedDebugAndroidTest`, 27 tests; `ParityTest` covers the groups that need Android), **D** seen on an Android 16 emulator, **L** live delivery to Scrawl on the Mac / a local HTTPS endpoint. Every item has a test or an observed run; no item rests on code alone.

| # | Item | Status | Evidence |
|---|---|---|---|
| 1 | `ScrawlConfig` value type with trimming/normalization and `isValid`. | ✅ | U ScrawlConfig parsing; L pairing via setup links (Mac, HTTPS, unreachable, reset) |
| 2 | `parseHTTPBaseURL`: trim, strip trailing slashes, http/https only, host required. | ✅ | U ScrawlConfig parsing; L pairing via setup links (Mac, HTTPS, unreachable, reset) |
| 3 | `validBaseURLs`: normalize, drop invalid, dedupe preserving order. | ✅ | U ScrawlConfig parsing; L pairing via setup links (Mac, HTTPS, unreachable, reset) |
| 4 | Setup URL parsing: any scheme, host `scrawl` or `tether`, repeated `url`, `token`, `session`. | ✅ | U ScrawlConfig parsing; L pairing via setup links (Mac, HTTPS, unreachable, reset) |
| 5 | Reset link `reset=1|true` (case-insensitive) wins over other params. | ✅ | U ScrawlConfig parsing; L pairing via setup links (Mac, HTTPS, unreachable, reset) |
| 6 | Invalid setup link (no URL or token) returns false and changes nothing. | ✅ | U ScrawlConfig parsing; L pairing via setup links (Mac, HTTPS, unreachable, reset) |
| 7 | `handleSetupURL` saves config, applies it with overrides, success haptic. | ✅ | I `setupLinksAreStoredPrivatelyAndAnnouncedOnce`; L setup/reset links on device |
| 8 | Reset deletes stored config, applies resolved (overrides only), warning haptic. | ✅ | I `setupLinksAreStoredPrivatelyAndAnnouncedOnce` (file deleted, warning haptic); L reset link |
| 9 | 250 ms duplicate-link suppression of haptics. | ✅ | I `setupLinksAreStoredPrivatelyAndAnnouncedOnce` (same link twice → one success haptic) |
| 10 | Sealed, no-backup persistent store for config JSON (`urls`, `token`, `sessionID`). | ✅ | I `setupLinksAreStoredPrivatelyAndAnnouncedOnce` (AES-256-GCM with an Android Keystore key, the Keychain's counterpart: the token isn't in the file; `noBackupFilesDir`); I `aPlainTextConfigIsMigratedToTheSealedStore` |
| 11 | Launch overrides (`scrawl-url` multi + comma split, `scrawl-token`, `scrawl-session`) via intent extras, same merge rules. | ✅ | U `launchOverridesLayerOverTheStoredConfig`; L `debug.scrawl.*`/Intent-extra overrides |
| 12 | `isConfigured` semantics (non-empty URLs + non-blank token). | ✅ | I `setupLinksAreStoredPrivatelyAndAnnouncedOnce`, `unpairedDevicesAreInert…` |
| 13 | Configuration-changed notification posted on next main-loop turn; installers resync. | ✅ | I `setupLinksAreStoredPrivatelyAndAnnouncedOnce` (listener on the next main turn); I windows wrapped/unwrapped on pair/unpair |
| 14 | Losing configuration mid-recording cancels without sending and hides overlay. | ✅ | I `unpairedDevicesAreInertAndUnpairingCancelsARecording` (cancelled, no overlay) |
| 15 | `setTeamEndpoint(url, token)` in-memory override; nil restores stored config. | ✅ | I `unpairedDevicesAreInertAndUnpairingCancelsARecording` (`setTeamEndpoint` and nil) |
| 16 | Complete inertness when unpaired (no observer, recognizer, overlay, keyboard tracking, network, files, outbox). | ✅ | I `unpairedDevicesAreInertAndUnpairingCancelsARecording` (no window wrapping, no overlay from prewarm) |
| 17 | `Scrawl.install(application, trigger, isEnabled)` (the `.scrawl()` counterpart); non-consuming deep-link handling, only while enabled and only on a fresh launch. | ✅ | D/L touches through wrapped window callbacks (Views, Compose, dialogs); setup links gated on `isEnabled` and `savedInstanceState == null` |
| 18 | Non-consuming touch observer on every app window (Window.Callback wrapper). | ✅ | D/L touches logged through wrapped window callbacks (Views, Compose, dialogs) |
| 19 | Three-finger long press: exactly 3 pointers, 500 ms, 10 dp allowable movement, fires once. | ✅ | I three pointers through the input dispatcher start once; I moving cancels; I injected trigger |
| 20 | Trigger: success haptic; starts, or sends if recording/starting. | ✅ | I three pointers through the input dispatcher start once; I moving cancels; I injected trigger |
| 21 | `ScrawlTrigger.none` (manual start/stop). | ✅ | I `withoutATriggerThreeFingersDoNothing` (three pointers through the dispatcher: no start, no prewarm); D manual buttons |
| 22 | Prewarm overlay on third finger down; transparent layout; 2 s expiry; `prewarm`/`prewarmLayout` events. | ✅ | I prewarm on third finger; I prewarmLayout/firstDraw ordering |
| 23 | `suppressesSystemEditingGestures` property kept (documented no-op on Android). | ✅ | I `withoutATriggerThreeFingersDoNothing` (property kept, default true; documented no-op) |
| 24 | Public `toggle`, `start`, `stop`, `discard`, `mark`, `toggleDrawing`, `prepareToStart`. | ✅ | D toggle/start/stop/discard/draw; L events on timeline |
| 25 | `Scrawl.event(label)` timeline events. | ✅ | D toggle/start/stop/discard/draw; L events on timeline |
| 26 | Observable state: `isRecording`, `isStarting`, `isDrawing`, `uploadStatus`, `startedAt`, `markers`, `completedStrokes`, `recordings`. | ✅ | D toggle/start/stop/discard/draw; L events on timeline |
| 27 | `narration`, `captureMethod`, `sharesWhenDone` options with iOS defaults. | ✅ | D toggle/start/stop/discard/draw; L events on timeline |
| 28 | `timelineSupplement` hook (keys overwrite). | ✅ | I `theSupplementLandsInTheTimelineAndASubmittedRecordingIsDeleted` (keys appended after built-ins) |
| 29 | `onRecordingSubmitted` hook (true → delete files, no upload). | ✅ | I `theSupplementLandsInTheTimelineAndASubmittedRecordingIsDeleted` (files deleted, no upload, idle) |
| 30 | `startupObserver` with exact stage names, once per trace, main-thread flag. | ✅ | I startupObserver stage order (`StartupTest`) |
| 31 | Start sequence: overlay shown first, hardware started after first presentation. | ✅ | I StartupTest (overlay before hardware, buffered send/discard, early drawing) |
| 32 | Duplicate start ignored; Send/Discard during start buffered. | ✅ | I StartupTest (overlay before hardware, buffered send/discard, early drawing) |
| 33 | Touches/ink accepted while starting; early input rebased to the movie clock. | ✅ | I StartupTest (overlay before hardware, buffered send/discard, early drawing) |
| 34 | Start failure: silent log, overlay hidden. | ✅ | I `failuresToStartOrStopEndQuietly` (recorder error → not starting, no overlay) |
| 35 | Recorder unavailable: silent abort. | ✅ | I `failuresToStartOrStopEndQuietly` (unavailable recorder → silent no-op) |
| 36 | Reachability probe `GET /health` with 4 s timeout at each start. | ✅ | L /health probe; L revoked (401) shown while recording |
| 37 | App-frame recorder: draws app windows only (overlay excluded), black fill, aspect fit. | ✅ | D crisp frames, overlay excluded; U canvas size 1.5×/1600 |
| 38 | Canvas 1.5 px/dp capped at 1600 px long edge, even dimensions. | ✅ | D crisp frames, overlay excluded; U canvas size 1.5×/1600 |
| 39 | H.264 High, 6 Mbps (Mac) / 3 Mbps (HTTPS), 30 fps, 1 s keyframes, no B-frames, BT.709, MP4. | ✅ | I on a Pixel 7: hardware `c2.exynos.h264.encoder` advertises High, the recorder requests it, the movie's SPS says profile 100; U `EncoderChoiceTest` (hardware High-profile encoder chosen); I `theMovieIsHighProfileH264WithoutBFramesAndAnEmptyOneFails` (6/3 Mbps VBR, 30 fps, 1 s GOP, BT.709, max B-frames 0, pts rise in decode order, High in the SPS where the encoder has it); L ffprobe |
| 40 | AAC mono 48 kHz 96 kbps microphone track on the same monotonic clock. | ✅ | I `anImmediateStopKeepsTheFirstFrame…` (no permission → one video track); L AAC 48 kHz track |
| 41 | Mic permission flow; denied → video only; `audio` flag from actual samples. | ✅ | I `theMicrophoneRestartsAfterItsInputGoesAway` (AAC 48 kHz mono); I no permission → video only |
| 42 | Audio resampling to 48 k mono, 50 ms resync/drop tolerance, 6-buffer pre-writer queue. | ✅ | U `audioRunsOnContiguouslyAndResyncsAfterRealGaps` |
| 43 | No audio focus request (mix with other audio); restart on routing/errors. | ✅ | I `theMicrophoneRestartsAfterItsInputGoesAway` (read failure → one restart, audio continues) |
| 44 | First frame stamped at start instant; movie t=0 = `startedAt`. | ✅ | I `anImmediateStopKeepsTheFirstFrameAndCancelIsSilent` (first sample t=0, key frame); L |
| 45 | Last frame repeated at stop time so movie length matches timeline. | ✅ | I `interactionSlowsCapture` (last sample at the stop instant, ±150 ms, repeated runs): frames are stamped with their capture time, as iOS stamps appended buffers, and the stream ends only once the stop frame is encoded |
| 46 | "no frames were recorded" error path; temp file moved atomically. | ✅ | I `theMovieIsHighProfileH264…` (nothing encoded → "no frames were recorded"); I temp → final rename |
| 47 | Background → stop and send (process lifecycle ON_STOP). | ✅ | D Home during recording → stopped and sent |
| 48 | FramePacer ported 1:1 with all constants and tests. | ✅ | U FramePacer suite ported |
| 49 | Choreographer-driven tick with interaction detection (touch moves, ink, scroll state). | ✅ | I `interactionSlowsCapture` (cadence skips, fewer frames while interacting) |
| 50 | Multi-window capture (dialogs/popups) in z-order. | ✅ | I `SurfaceCaptureTest` (SurfaceView and TextureView pixels in the movie, views over them kept on top); D dialog recorded and overlay raised above it |
| 51 | Keyboard tracker (IME insets, animated frame, cubic ease-out fallback). | ✅ | I keyboard panel decoded from a real movie (grey panel + label pixels); U tracker easing |
| 52 | Keyboard panel stand-in ("Keyboard" badge, light/dark colors, separator). | ✅ | I keyboard panel decoded from a real movie (grey panel + label pixels); U tracker easing |
| 53 | `keyboard` context value: visible / hidden, cleared at start when down. | ✅ | U `keyboard` visible / hidden / cleared |
| 54 | MediaProjection recorder (consent, FGS type mediaProjection, includes overlay + IME). | ✅ | L MediaProjection recording delivered, `capture: mediaProjection` |
| 55 | `capture` value in timeline per method. | ✅ | L MediaProjection recording delivered, `capture: mediaProjection` |
| 56 | HTTPS length cap: 180 s app frames / 45 s screen capture, 10 s warning notice + warning haptic, auto-send. | ✅ | I length-limit notice then auto-send (shortened cap) |
| 57 | Touch IDs: sequential from 1 per recording; pre-existing fingers ignored; reset on all-up; stationary fingers aren't logged as moves. | ✅ | I touches/hold test; L delivered timelines |
| 58 | Touch phases `down/move/up/cancel`; dp coordinates in the app window (a dialog's touches mapped onto it, as iOS's full-screen windows), 3-dp rounding; `screen` is that window (zeros with no touches, as iOS). | ✅ | I `touchesInADialogAreInTheAppWindowsCoordinates`; L delivered timelines |
| 59 | Move throttle 1/30 s per touch; wall-clock `t`. | ✅ | L touch ids/phases/dp coords/throttle in delivered timelines |
| 60 | Hit label lookup (a11y label, button title, text, nearest child label ≤110 dp, 7 levels, depth 3). | ✅ | L View labels (Row …, Open detail, EditText hint) |
| 61 | Compose semantics label lookup. | ✅ | L Compose labels (Save draft, Add to favorites, Subtitle…, Compose note) |
| 62 | Label cleaning: whitespace collapse, 48 chars + "…". | ✅ | U `labelsCollapseWhitespaceAndCapAt48` |
| 63 | Hold tick: rigid haptic after 420 ms within 12 dp. | ✅ | I `touchesAreLoggedWithAHoldTickButNotOnTheStrip` (one rigid tick at 500 ms; none after moving 60 dp) |
| 64 | Touches on overlay/strip/ink not logged. | ✅ | I `touchesAreLoggedWithAHoldTickButNotOnTheStrip` (strip tap not on the timeline); I drawing touches not logged |
| 65 | Timeline file: all keys per §7, pretty + sorted keys. | ✅ | L delivered timeline keys, UTC stamp names, app/version/device/screen |
| 66 | `recordedAt` ISO-8601 UTC second precision with `Z`. | ✅ | L delivered timeline keys, UTC stamp names, app/version/device/screen |
| 67 | File stem `yyyy-MM-dd'T'HHmmss` (UTC). | ✅ | L delivered timeline keys, UTC stamp names, app/version/device/screen |
| 68 | `app`, `bundleId`, `version` (versionCode), `shortVersion` (versionName), `device`, `system`, `screen{width,height,scale}`. | ✅ | L delivered timeline keys, UTC stamp names, app/version/device/screen |
| 69 | `duration` measured at Send/Discard. | ✅ | L delivered timeline keys, UTC stamp names, app/version/device/screen |
| 70 | `session` key only when sessionID non-empty. | ✅ | L delivered timeline keys, UTC stamp names, app/version/device/screen |
| 71 | Screen naming: `Scrawl.screen`, `ScrawlScreen` composable/View helper with token stacks. | ✅ | U `theLatestSetterStillPresentWinsAndChangesAreTimed`; L `screens` on device |
| 72 | Context values: `Scrawl.context`, `ScrawlContext` composable with promote/update/remove semantics. | ✅ | U `theLatestSetterStillPresentWinsAndChangesAreTimed`, `blankValuesClear…`; L |
| 73 | `context`, `contextChanges` (null on clear), `screens` (start screen at t 0) in the timeline. | ✅ | U `theLatestSetterStillPresentWinsAndChangesAreTimed` (ms rounding, null clears) |
| 74 | Ink overlay: drawing mode takes all touches; one finger draws; others ignored. | ✅ | I `aDrawnStrokeIsRecordedAndFades` (drawing takes touches; MotionPredictor on API 34+); D |
| 75 | Coalesced (historical) samples + predicted tip (motion prediction), predictions never recorded. | ✅ | I `aDrawnStrokeIsRecordedAndFades`; D list doesn't scroll while drawing |
| 76 | One-euro filter (3, 0.03, 1) and speed-based width (4.6 dp, 55% fast, 1600 dp/s). | ✅ | U one-euro, widths, Catmull-Rom, tapers, holes |
| 77 | Centered smoothing + centripetal Catmull-Rom outline with tapers (10 dp, 0.35 tip). | ✅ | U one-euro, widths, Catmull-Rom, tapers, holes |
| 78 | Frozen chunk layers (48 points) so only the tip redraws. | ✅ | I `aDrawnStrokeIsRecordedAndFades`; U InkFade; D tapered stroke |
| 79 | Stroke fade: 1.5 s hold, 0.3 s ease-out, grouping; `fadeAt` recorded. | ✅ | I `aDrawnStrokeIsRecordedAndFades` (stroke `t0`/`t1`/points on the timeline) |
| 80 | Strokes in timeline: `t0`, `t1`, `points [[x,y,t]]` on recording clock. | ✅ | I on a Pixel 7, 6/6 runs (a fade timer that woke a fraction of a millisecond early used to skip the fade: now rounded up and rescheduled); I `aDrawnStrokeIsRecordedAndFades` (`fadeAt` after the 1.5 s hold); U InkFade |
| 81 | Ink cleared at start (instant) and finish (0.2 s fade). | ✅ | I `aDrawnStrokeIsRecordedAndFades` (cleared on stop) |
| 82 | Overlay host above the app with region hit-testing (+8 dp), excluded from app-frame capture. | ✅ | I `touchesAreLoggedWithAHoldTickButNotOnTheStrip` (strip region takes its touches); I keyboard panel excludes overlay; D over dialogs |
| 83 | Overlay follows the foreground Activity; linger ≈0.97 s after exit. | ✅ | U `theOverlayLingersForTheExitToSettle`; I overlay gone after linger; D follows activities |
| 84 | Strip controls: dot, Draw, divider, Discard, Send with exact sizes, icons, a11y strings. | ✅ | D controls, pulsing dot + minute ring; D hold dot → elapsed bubble |
| 85 | Recording dot: minute ring, pulsing red dot, spoken elapsed label. | ✅ | D controls, pulsing dot + minute ring; D hold dot → elapsed bubble |
| 86 | Hold dot → elapsed `m:ss` bubble. | ✅ | D controls, pulsing dot + minute ring; D hold dot → elapsed bubble |
| 87 | Drag strip (8 dp threshold), throw to nearer edge, persisted side/position. | ✅ | U `aThrowStretchesTheStripAlongIt`; D drag to the other edge, remembered |
| 88 | Strip area: safe area, 6/8 dp insets, above the keyboard (following its end position as it rises and falls). | ✅ | I `theStripMovesAboveARealKeyboardAndBack` (a real IME, Pixel 7 + emulator); I `theStripRestsInTheSafeAreaAndAboveTheKeyboard` |
| 89 | Liquid spring model with all springs/timings; display-link driven only while moving. | ✅ | U `aDropletStretchesIntoTheStripAndTheControlsFanOut`; D |
| 90 | Transitions: droplet → strip, pour into status circle, bubble bud/absorb, slide/shrink exit, failed shake, gulp. | ✅ | U `sendingPoursIntoTheStatusCircleAndBudsItsBubbleLater`; D |
| 91 | Throw stretch from velocity. | ✅ | U `aFailedSendShakesAndOffersShare`, `leavingFadesTheGlassAway…`; D |
| 92 | Glass body + bubble with metaball neck; dark translucent tint. | ✅ | I `theStripIsLiquidGlassWithALitRim` (Liquid Glass on API 33+, lit rim vs tinted interior on a screenshot); I `beforeAndroid12TheStripBlursTheAppOnTheCpu` on Android 9 (the app's colour shows through the tint); U `SoftwareBlurTest`; D lensing over the sample's buttons |
| 93 | Reduce Motion cross-fade (0.11 s). | ✅ | U `reduceMotionCrossFadesInsteadOfMorphing` |
| 94 | Status icons: progress ring / spinner, check, queued tray, warning triangle, trash. | ✅ | D Sending…, Sent to …, Saved on your Mac, Queued · sends when online, Mac didn't answer, link expired, Discarded · Undo |
| 95 | Bubble texts exactly as §10.3 (Sending…, Still sending…, Sent to …, On your Mac …, Saved on your Mac, Queued · sends when online, failures, Discarded · Undo). | ✅ | D Sending…, Sent to …, Saved on your Mac, Queued · sends when online, Mac didn't answer, link expired, Discarded · Undo |
| 96 | Title shortening (36 / 24 / 30 chars + "…"). | ✅ | D Sending…, Sent to …, Saved on your Mac, Queued · sends when online, Mac didn't answer, link expired, Discarded · Undo |
| 97 | Notices while recording (link expired 5 s, length limit). | ✅ | D link-expired notice while recording |
| 98 | Strip mode precedence (recording > prepared > discarded > upload status). | ✅ | I `aRecordingIsPostedToTheMacAndTheStripSaysSent`, `olderFailuresWaitForAnIdleStrip` (precedence) |
| 99 | Status durations: sent 2.2/3.5 s, queued 3.5 s, failed 8 s, statusID cancellation. | ✅ | I `aRecordingIsPostedToTheMacAndTheStripSaysSent` (Sent 2.2 s → idle → gone) |
| 100 | Haptics mapping per §11. | ✅ | I `aRecordingIsPostedToTheMacAndTheStripSaysSent` (medium/light/light/success), `setupLinks…` (success/warning) |
| 101 | Discard with 5 s Undo window; Undo during write; finalize on new recording. | ✅ | D Discard → Undo still sends |
| 102 | LAN delivery: `.ready` marker, framed payload, parallel POSTs, `X-Scrawl-Wait` 15/45, timeout wait+15. | ✅ | L LAN POST to Mac (sent to herdr) |
| 103 | Reachable URL used alone when the probe found one. | ✅ | L LAN POST to Mac (sent to herdr) |
| 104 | USB `.sent` watch (75 × 400 ms) reading `.delivery` reply. | ✅ | L USB: Mac adb poller pulled, replied; phone cleaned markers |
| 105 | Success cleans markers, keeps `.mp4`/`.json`; failure keeps `.ready`, shows "Mac didn't answer" + Share. | ✅ | L USB: Mac adb poller pulled, replied; phone cleaned markers |
| 106 | `recordings` list (LAN only, in memory). | ✅ | I `aRecordingIsPostedToTheMacAndTheStripSaysSent` (recordings list, `.ready` cleared on delivery); U IngestReply |
| 107 | `IngestReply` parsing incl. legacy (no `delivered`) and USB markers. | ✅ | I `anUnreachableMacKeepsTheRecording…` (`.ready` kept for USB pickup) |
| 108 | HTTPS: outbox only when an https URL is configured; torn down when not. | ✅ | L HTTPS outbox over TLS; U Outbox suite |
| 109 | Outbox entry files (`.payload`, `.json`), streamed write, id format, size check 95 MB. | ✅ | L HTTPS outbox over TLS; U Outbox suite |
| 110 | Outbox storage in no-backup, post-first-unlock storage. | ✅ | L HTTPS outbox over TLS; U Outbox suite |
| 111 | Staging directory for hosted recordings; staged files deleted after enqueue. | ✅ | L HTTPS outbox over TLS; U Outbox suite |
| 112 | Background upload worker surviving process death, file-based body, Content-Length. | ✅ | L HTTPS outbox over TLS; U Outbox suite |
| 113 | Headers: `Authorization: Bearer`, `X-Scrawl-Token`, `Content-Type`, `Content-Length`. | ✅ | L HTTPS outbox over TLS; U Outbox suite |
| 114 | Status classification (2xx / 401·403 / 413 / 408·429 / other 4xx / rest). | ✅ | L HTTPS outbox over TLS; U Outbox suite |
| 115 | Backoff 15 s doubling to 30 min; `nextAttemptAt` persisted. | ✅ | L HTTPS outbox over TLS; U Outbox suite |
| 116 | Kick triggers: launch, foreground, network regained, after enqueue, after retry. | ✅ | L retries after 503 delivered both entries; U kicks/in-flight/progress |
| 117 | Never two concurrent uploads of one entry; adopt in-flight work after relaunch. | ✅ | L retries after 503 delivered both entries; U kicks/in-flight/progress |
| 118 | Progress events per whole percent or every 5 s. | ✅ | L retries after 503 delivered both entries; U kicks/in-flight/progress |
| 119 | Permanent failures kept on disk with Share; `retryFailed` on new link (target/token change). | ✅ | L revoked → kept as unauthorized; renewed link → sent |
| 120 | Revoked pairing (`/health` 401/403 on https) → notice, entry enqueued as failed `unauthorized`. | ✅ | L revoked → kept as unauthorized; renewed link → sent |
| 121 | DeliveryTracker: Sending / Still sending (30 s) / Queued (offline or waiting) / Sent / Failed. | ✅ | U DeliveryTracker; L Queued shown on device |
| 122 | Network path monitoring (default network callback). | ✅ | U DeliveryTracker; L Queued shown on device |
| 123 | Queued → Sending when a retry starts while shown. | ✅ | U DeliveryTracker; L Queued shown on device |
| 124 | Pending "Sent to …" shown later when app returns to foreground / strip frees up. | ✅ | L 'Sent to …' shown on return after a background upload |
| 125 | Outbox failures for older entries shown only when idle. | ✅ | I `olderFailuresWaitForAnIdleStrip` |
| 126 | Share fallback: ACTION_SEND_MULTIPLE via FileProvider; outbox extraction to temp copies, cleaned after. | ✅ | L Share sheet with .mp4 + .json; I `anUnreachableMacKeepsTheRecording…` |
| 127 | `sharesWhenDone = false` makes Share just dismiss. | ✅ | I `anUnreachableMacKeepsTheRecordingAndShareFollowsSharesWhenDone` (no chooser when false) |
| 128 | Stop/write failure: log, idle, overlay exit (no bubble). | ✅ | I `failuresToStartOrStopEndQuietly` (stop failure → idle, strip leaves) |
| 129 | USB path for Android (adb file poller or `adb reverse`), with the required Mac changes. | ✅ | L adb pull + `.delivery`/`.sent` push (Mac `poll_adb_app_dirs`) |
| 130 | Cleartext HTTP configuration guidance for LAN URLs. | ✅ | README "Plain HTTP to your Mac"; sample `network_security_config`; I the test APK's cleartext manifest; L LAN delivery over http |
| 131 | Debug-only packaging (`debugImplementation` + no-op release artifact). | ✅ | L release APK uses scrawlkit-noop: no permissions |
| 132 | Unit tests ported: FramePacer, Ink, DeliveryTracker, IngestReply, Outbox, startup ordering. | ✅ | U 53 tests; I 10 tests |

How it's done differs where Android's APIs differ, with the same behaviour (README "Platform differences from iOS"): touches pass through by routing in window callbacks (Android windows can't be partly touchable), the overlay becomes a strip-sized window under TalkBack, the strip's area is excluded from the back gesture, and the editing-gesture switch has no in-app gestures to turn off. Every version from minSdk 26 gets the blurred material: GPU blur on 12+, CPU blur (`SoftwareBlur`) on 8–11.

## 16. Independent review

A separate review read the iOS sources and this port side by side (without this document) and
listed every behavioural mismatch it could verify. All of them were fixed and tested:

| Finding | Fix | Evidence |
|---|---|---|
| Strip didn't move when the keyboard rose or fell | keyboard target drives the strip's rest; read each overlay frame (edge-to-edge windows don't relayout) | I `theStripMovesAboveARealKeyboardAndBack` (real IME) |
| Dialog touches in the dialog's coordinates; `screen` could be the dialog's | touches mapped into the activity window; `screen` from it | I `touchesInADialogAreInTheAppWindowsCoordinates` |
| Unpairing didn't cancel uploads in flight | `Uploader.cancelAll` (URLSession `invalidateAndCancel`) | U `unpairingCancelsAnUploadInProgress`, `httpUploaderCancelAllAbortsABlockedUpload` |
| Offline launch never retried on reconnect | starting state seeded from `activeNetwork`; iOS `Reachability` | U `ReachabilityTest` |
| Health probe waited for every address; revoked despite a LAN 200 | first 200 wins and the rest are dropped; revoked only if none answered | U `HealthProbeTest` |
| Setup links handled while disabled / on activity recreation | gated on `isEnabled` and a fresh launch | code + I setup-link tests |
| Stationary fingers logged as moves | only moved pointers logged | I touches test |
| USB-wait race | tasks counted before any starts | code |
| Context before `install()` lost | one process-wide store, as iOS's singleton | I `contextSetBeforeInstallIsKept` |
| Outbox retried on every activity resume | only when the app comes to the foreground | code |
| Startup stage order; missing microphone stages | writer set up off the main thread in parallel with the permission; `audioSession*`, `engine*`, `microphoneReady` marked | I StartupTest |
| Reply parsing on odd types | Swift's casts: 0/1 bridge to Bool, non-string titles/states ignored | U `ReplyCastTest` |
| Stop drew a fresh frame | the last captured frame is appended again (display list, API 29+) | I `theStopRepeatsTheLastCapturedFrame` |
| Undo hit area 8 dp (iOS 10) | 10 dp for Undo, 8 for Share | code |
| No settable `serverURLs`/`token`/`sessionID`, no `activeStroke` | added (setters notify as iOS's `didSet`) | compile + noop mirror |
| No Compose `.scrawlScreen` / `.scrawlContext` | `Modifier.scrawlScreen` / `scrawlContext` | I `ComposeContextTest` |
| No signposts | `Trace` sections `CaptureDraw` / `VideoAppend`; `CaptureSkip` / `ScrawlStartup` events in the log | code |
| Config "encrypted" claim | sealed with an Android Keystore key | I sealed-store tests |
| Screen capture sent the recording when the app went to the background (ReplayKit doesn't stop) | projection detached from the encoder in the background and reattached on return | L Pixel 7: 4 s on the home screen mid-recording → one 3.7 s gap in the movie, recording went on and was delivered over USB |
| Overlay linger from this port's spring model (0.924 s) | measured SwiftUI's own `Spring(duration:bounce:).settlingDuration` on macOS for every strip spring and used those values (exitFade 0.700 s), so the linger is iOS's exact 970 ms | U `theOverlayLingersForTheExitToSettle` (0.700 s, 970 ms) |

