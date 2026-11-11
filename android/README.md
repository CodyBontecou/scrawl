# ScrawlKit for Android

The Android port of ScrawlKit: record your app's screen with narration, the touches that drove it, markers, freehand red strokes, app events, and the screen/context the app declares, all on one clock, and send it straight to Scrawl on your Mac (or an HTTPS endpoint), which builds the bundle and hands it to your agent. The wire format, timeline schema, strip, and delivery rules match the iOS ScrawlKit (`Sources/ScrawlKit`); `PARITY.md` is the feature-by-feature spec it was built against.

## Install

`settings.gradle.kts` (composite build from a checkout of this repo, or publish the modules):

```kotlin
includeBuild("../scrawl/android")
```

`app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        // The scheme of your setup links: `scrawl link --scheme voxmd` → voxmd://scrawl?…
        manifestPlaceholders["scrawlScheme"] = "voxmd"
    }
}

dependencies {
    debugImplementation("dev.kitlangton.scrawl:scrawlkit")
    // Same API, does nothing: release builds get no permissions, activities, services or network.
    releaseImplementation("dev.kitlangton.scrawl:scrawlkit-noop")
}
```

`Application.onCreate`:

```kotlin
Scrawl.install(this)                         // three-finger long press starts and stops
Scrawl.event("Done tapped")                  // optional: what the app did, on the timeline
ScrawlScreen.attach(view, "Settings")        // optional: which screen this is (like .scrawlScreen)
Scrawl.context("session", "ses_…")           // optional: app-defined values
Modifier.scrawlScreen("Inbox")               // Compose: like SwiftUI's .scrawlScreen / .scrawlContext
Modifier.scrawlContext("thread", threadId)
```

That's all: ScrawlKit wraps each app window's touch callback (Views, Compose and React Native alike), so there's nothing to add to your layouts. It is completely inert until the device is paired.

### Plain HTTP to your Mac

Macs on the LAN or tailnet are `http://` addresses, which Android blocks by default. Allow cleartext in debug builds, for example with `android:usesCleartextTraffic="true"` in `src/debug/AndroidManifest.xml`, or a debug `network_security_config` that permits your Mac's addresses.

## Pairing

```sh
scrawl link --scheme voxmd
adb shell am start -a android.intent.action.VIEW -d "'voxmd://scrawl?url=…&token=…'"
```

(or open the link / scan the QR on the phone). `voxmd://scrawl?reset=1` unpairs. The pairing is stored sealed with an Android Keystore key (iOS keeps it in the Keychain), outside backups. For an emulator, the Mac is `http://10.0.2.2:47777`. Debug overrides, like iOS's launch arguments: launch Intent extras `scrawl-url`, `scrawl-token`, `scrawl-session`, or `adb shell setprop debug.scrawl.url …` (`.token`, `.session`).

## Delivery

- **Mac (http URLs):** the recording is posted to every configured address at once (`X-Scrawl-Wait`), and the strip says what the Mac did: "Sent to …", "On your Mac · Sends to … when it can", "Saved on your Mac".
- **USB:** recordings are kept in the app's external files `Scrawl/` folder with a `.ready` marker. With the phone cabled (or an emulator running) and the app's package in `usbApps` in `~/.config/scrawl/config.json`, Scrawl on the Mac pulls them over `adb` and writes `.delivery`/`.sent` back, the same handshake as `devicectl` on iOS. `adb reverse tcp:47777 tcp:47777` with `http://127.0.0.1:47777` also works.
- **HTTPS endpoints:** a durable outbox (`noBackupFilesDir/ScrawlOutbox`), retried with backoff and finished by WorkManager if the app is killed.
- **Neither answers:** "Mac didn't answer" with Share (the `.mp4` and `.json` through the system share sheet).

## Capture methods

- `ScrawlCaptureMethod.AppFrames` (default): draws the app's own windows (activities, dialogs, popups) into an H.264 movie at 1.5 px/dp, paced by the same `FramePacer` as iOS, with the microphone as AAC. No screen-capture consent. The strip and ink stay out of the movie; the soft keyboard is drawn as a labelled panel. SurfaceView content (video players, cameras, maps) is composited in with `PixelCopy`, behind the views over it, and TextureViews are drawn with their window, the way iOS's `drawHierarchy` snapshot includes such layers.
- `ScrawlCaptureMethod.MediaProjection`: the exact screen, behind Android's "Share your screen" consent each recording (the ReplayKit counterpart), with a foreground service while it runs.

## Platform differences from iOS

- The overlay passes touches through by routing them in the app windows' callbacks (Android windows can't be touchable in only part of their area). With TalkBack's touch exploration on, the overlay becomes a touchable window sized to the strip so TalkBack can reach it.
- The strip's area is excluded from Android's back gesture so dragging it from the edge moves it.
- `suppressesSystemEditingGestures` turns off iOS's in-app three-finger editing gestures; Android apps have none, so a three-finger hold already reaches the trigger and the switch has nothing to do. (System-wide gestures, such as iOS Zoom's or some Android phones' three-finger screenshot, are outside any app on either platform.)
- The strip is Liquid Glass on Android 13+ (an AGSL lens, rim and tint over the blurred app, as iOS 26's `glassEffect`) and the blurred material below that (as iOS before 26): GPU-blurred on Android 12, CPU-blurred from a small copy of the app on Android 8–11.
- Haptics go through `View.performHapticFeedback` (no VIBRATE permission).

## Tests

```sh
./gradlew :scrawlkit:testDebugUnitTest            # FramePacer, Ink, Outbox, DeliveryTracker, IngestReply, keyboard tracker, setup links/overrides, context stacks, strip motion
./gradlew :scrawlkit:connectedDebugAndroidTest    # (run on Android 16 and Android 9) startup ordering, three-finger trigger, strip a11y, keyboard panel, and ParityTest: pairing, inertness, delivery to a fake Mac, failures, touches, ink, movie format, mic restart, strip placement
```
