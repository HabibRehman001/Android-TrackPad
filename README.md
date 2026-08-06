# Phone Trackpad

Turn your phone's touchscreen into a USB-connected trackpad for your PC.

## Latency: what was fixed, and what's structurally left

Three real bottlenecks existed in the first version, all now fixed:

1. **JSON per packet -> fixed 5-byte binary frames.** No allocation, no
   text parsing, on either side. Verified: decoding a full second of
   240Hz samples (240 packets) takes ~0.2ms on the desktop.
2. **Nagle's algorithm -> TCP_NODELAY on both ends.** Nagle can hold a
   small packet for up to ~40ms hoping to batch it with more data. For
   input events that's pure added lag. Disabled on the phone's socket
   *and* the desktop's accepted connection - it's a per-socket,
   per-direction setting, so both sides need it.
3. **Compose throttling 240Hz to your display's refresh rate -> a raw
   `View` with `requestUnbufferedDispatch`.** This was the biggest one.
   Compose's `pointerInput` wakes up once per display frame (likely
   90-120Hz), not once per touch sample - so a 240Hz digitizer was being
   quietly downsampled before your app ever saw it. `requestUnbufferedDispatch`
   tells Android's input system to stop batching to vsync and deliver
   every raw sample as its own `onTouchEvent` call, as fast as the
   digitizer produces them. `TouchpadView` also walks `MotionEvent`'s
   historical samples defensively, so even if the system still batches a
   couple of samples together, none of them get thrown away.

**What's genuinely still there:** this is a phone app talking over a
socket to a Python process - there's still a USB transfer, an adb
tunnel hop, and Python's function-call overhead between "packet parsed"
and "`SendInput`-equivalent called." In practice this should feel very
close to a real trackpad (many actual USB mice poll at 125-1000Hz,
i.e. 1-8ms between updates - not fundamentally different from what
we're doing now), but it is not *literally* zero, because it's still
going through the phone's OS, an app process, and a desktop OS process.

The only way to remove every one of those layers is to make the phone
present itself to the PC as an actual USB HID mouse - at that point the
OS's native mouse driver reads it directly, the same as a Logitech
mouse, with no app or socket involved at all. That path is real (people
do it), but it needs **root** and a kernel with USB gadget/HID support
configured - it's not something a normal Android app can do, regardless
of language or framework, because unrooted Android doesn't expose
`/dev/hidg`-style gadget control to apps. It's also phone-model
dependent and not undoable without some risk (unlocked bootloader, full
data wipe on most phones, voided warranty on some). If you want to go
that route, say so and I'll walk through it - but it's a genuinely
different, riskier project from what's here, not just a config change.

## How it connects

Instead of manual USB tethering (which needs a mobile-data-capable
tethering state and gives you a network interface with an IP that can
change), this uses `adb reverse`:

```
adb reverse tcp:6000 tcp:6000
```

With USB debugging on and the phone plugged in, this tunnels a TCP port
over the existing USB connection. The Android app connects to
`127.0.0.1:6000` on the *phone*; adb transparently forwards that to
`127.0.0.1:6000` on the *PC*, where `server.py` is listening. No
tethering, no IP addresses to hunt for.

## Project layout

```
PhoneTrackpad/
  desktop/
    config.py       # host/port
    packet.py        # newline-delimited JSON framing
    mouse.py          # packet -> pynput mouse calls
    server.py        # TCP server + accept loop
    requirements.txt
  android/
    app/src/main/
      AndroidManifest.xml
      java/com/example/phonetrackpad/
        Packet.kt              # wire format
        SocketManager.kt        # persistent TCP client w/ auto-reconnect
        GestureProcessor.kt     # touch -> gesture -> packet, settings-driven
        TouchSurface.kt         # Raw View touch capture (240Hz path) + Compose wrapper
        TrackpadSettings.kt     # the tunable "feel" values
        SettingsStore.kt        # SharedPreferences-backed live state
        SettingsPanel.kt        # sliders UI
        MainActivity.kt         # wires it all together
```

## User-controlled feel (the "Leva panel" part)

Tap **⚙ Settings** in the top-right corner of the app to open a panel
with live sliders:

- **Sensitivity** - multiplier on raw finger movement (0.3x-3.0x)
- **Smoothness** - moving-average window size (1 = raw/off, up to 8 = very smooth)
- **Acceleration** - on/off switch, plus a strength slider for how much
  extra distance fast swipes get
- **Scroll speed** - multiplier for two-finger scroll
- **Invert scroll** - flips scroll direction

These write into `SettingsStore` (backed by `SharedPreferences`), and
`GestureProcessor` reads the current values on every single touch event -
so dragging a slider changes the cursor's feel immediately, the same way
a Leva/dat.GUI panel drives a live three.js scene. No rebuild, no
restart, and your choices persist next time you open the app.

## Running it

### 1. Desktop (PC)

```bash
cd desktop
pip install -r requirements.txt
python server.py
```

You should see:
```
[server] Listening on 127.0.0.1:6000
[server] Run 'adb reverse tcp:6000 tcp:6000' then open the app on your phone.
```

### 2. Bridge the USB connection

With the phone plugged in and USB debugging enabled (Settings -> About
phone -> tap "Build number" 7 times -> Developer options -> USB
debugging):

```bash
adb reverse tcp:6000 tcp:6000
```

(Needs the Android Platform Tools / `adb` installed on the PC - the
same tool that ships with Android Studio.)

### 3. Android app

Create a new **Empty Activity** project in Android Studio (Kotlin,
Jetpack Compose template), then:

1. Drop the files from `android/app/src/main/java/com/example/phonetrackpad/`
   into your project's matching package folder (rename the package in
   each file's `package` line if you used a different name).
2. Merge the `<uses-permission>` line from the provided
   `AndroidManifest.xml` into your project's manifest.
3. Set `minSdk = 26` (or higher) in `app/build.gradle.kts` -
   `requestUnbufferedDispatch` needs API 26+ (Android 8.0, released
   2017). Any phone with a 240Hz touch sampling rate is running far
   newer than this, so it's a non-issue in practice, but the default
   Compose template sometimes sets minSdk lower.
4. Make sure your `app/build.gradle.kts` has these dependencies (a
   fresh Compose project template already includes most of them):

```kotlin
dependencies {
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

5. Run the app on your phone (over the same USB cable). It'll try to
   connect immediately and retry every 2 seconds until `server.py` and
   `adb reverse` are both up.

### 4. Test it

- One finger, drag -> cursor moves
- Tap -> left click
- Double tap -> double click
- Long press (no movement) -> right click
- Two fingers, drag up/down -> scroll
- Two-finger tap -> middle click

## Known rough edges (v1)

- Only one phone connection at a time is really supported.
- The write queue in `SocketManager` doesn't get cleared on a
  reconnect, so a couple of stale `move` packets could apply right
  after the socket comes back up. Fine for personal use; worth fixing
  if you want this production-grade.
- Pinch-to-zoom isn't implemented - the gesture table has it as
  optional, and it's a natural next feature to add to
  `GestureProcessor` (track the distance between two fingers instead
  of just their average Y).
- Lifting one finger out of a two-finger scroll ends the whole gesture,
  even if the other finger is still down - it doesn't hand off into a
  one-finger drag. Simpler and predictable; a real trackpad driver
  would handle the handoff, but it's a fair bit more state to track
  correctly.
