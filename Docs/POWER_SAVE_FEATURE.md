# Power Save (Idle Node Shutdown) — Feature Design

Status: **Draft — approved for implementation**
Author: design brainstorm with maintainer, 2026-08-29

## 1. Summary

When enabled, Power Save automatically stops the Yggdrasil node (but keeps the
Android foreground service alive) after a configurable period during which
**zero** SOCKS-proxy / forwarded-port connections were active. While the node
is powered down, the app keeps lightweight placeholder listeners bound to the
same local addresses/ports that SOCKS proxy and forward mappings normally use.
Any new connection attempt (or a manual "Wake Now") brings the node back up
and re-establishes the real forwarding, at which point the client can retry
its connection.

Power Save only concerns **local-origin** traffic (SOCKS proxy, forwarded
ports). It is unavailable while any **exposed** port mapping is enabled,
because exposed ports accept connections *from* the Yggdrasil network, which
requires the node to already be up to receive them — there is nothing local
to "listen and wake on."

## 2. Goals / Non-goals

**Goals**
- Reduce battery/network/CPU usage during long idle periods for users who
  only use forwarded ports / SOCKS proxy occasionally.
- Make idle shutdown and wake-up transparent and safe — no data loss beyond
  the single triggering connection attempt, no crash loops, no surprise
  disconnects for exposed-port users.
- Keep the feature fully optional and off by default.

**Non-goals**
- Not attempting to seamlessly complete the very first "wake" connection
  (client must retry — see §4.3).
- Not supporting power save while any exposed mapping is active.
- Not changing behavior when Power Save is disabled (fully backward
  compatible).

## 3. Current architecture (relevant facts)

- `YggstackService` (foreground `Service`) owns the Go `Yggstack` instance.
  `startYggstack()` / `stopYggstack()` fully create/destroy it.
- **Local listening sockets live inside the Go node itself**:
  - SOCKS proxy: `yggstack.start(socksAddress, dnsServer)`.
  - Forward mappings: `yggstack.addLocalTCPMapping(localAddr, remoteAddr)` /
    `addLocalUDPMapping(...)`.
  - ⇒ When the node stops, these sockets disappear. To "keep listening while
    idle" the app must bind its own placeholder listener on the same
    `local address:port` while the node is down.
- Exposed mappings (`addRemoteTCPMapping`/`addRemoteUDPMapping`) listen on the
  **Yggdrasil-network side**; they cannot be replaced by a local placeholder,
  which is why they are incompatible with Power Save.
- `stopYggstack()` currently ends with `stopSelf()` — it fully tears down the
  service. Power Save needs a **new idle-stop path** that stops the Go node
  without killing the Android service or its notification.
- Connection counts are already tracked per listener via
  `PortStatsDetail(kind, activeConnections, ...)`, polled every 1s from
  `yggstack.getListenersJSON()` and exposed as `portStatsJSON` /
  `DiagnosticsViewModel.portStats`. `PortStatsDetail.section` maps
  `kind` → `"proxy"` (socks), `"forward"` (local-tcp/local-udp), `"expose"`
  (remote-tcp/remote-udp). **Idle detection sums `activeConnections` over
  `section in {"proxy", "forward"}`, ignoring `"expose"`.**
- Config persistence: `ConfigRepository` (DataStore `Preferences`), one key
  per field, assembled into `YggstackConfig` via `configFlow`.
- UI state: `ServiceState` sealed class (`Stopped`, `Starting`, `Running`,
  `Stopping`, `Error`) drives `ConfigurationScreen`'s start/stop button and
  status displays.
- **Peer stats and port stats are currently one combined poll loop**:
  `startPeerStatsSubscriptionMonitor()` watches
  `_peerDetailsJSON.subscriptionCount + _portStatsJSON.subscriptionCount` and,
  whenever the sum is `> 0`, runs a single `startPeerStatsUpdater()` loop on a
  fixed 1s `delay(1000)` that fetches **both** `getPeersJSON()` (IP, public
  key, peer counts, notification text) **and** `getListenersJSON()` (port
  stats) every tick. Today this only runs while the Peers or Ports tab is
  open — the Ports tab drives port-stats polling, there is no independent
  "headless" port-stats polling while the app is backgrounded.
- **Wake locks held by the service** (all in `YggstackService`):
  | Lock | Type | Tag | Current acquire | Current release |
  |---|---|---|---|---|
  | `wakeLock` | `PowerManager.PARTIAL_WAKE_LOCK` | `YggstackService::WakeLock` | `onCreate()` — i.e. for the **entire service lifetime**, independent of whether the node is running | `onDestroy()` only |
  | `wifiLock` | `WifiManager.WifiLock` (`WIFI_MODE_FULL_HIGH_PERF` if multicast enabled, else `WIFI_MODE_FULL`) | `YggstackService::WifiLock` | `startYggstack()`, only if on Wi-Fi | `stopYggstack()` (both success and error paths) |
  | `multicastLock` | `WifiManager.MulticastLock` | `YggstackService::MulticastLock` | `startYggstack()`, only if multicast enabled + on Wi-Fi | `stopYggstack()` and `onDestroy()` |

  `wifiLock`/`multicastLock` are already scoped to the running lifecycle, so
  the existing idle-shutdown stop path (§4.4) already releases them for
  free. `wakeLock` is the one that needs to change scope — see §4.8.

## 4. Behavior design

### 4.1 Feature availability / eligibility

Power Save toggle is only meaningful when there is something to wake on:

```
hasActiveExposedPorts(config) = config.exposeEnabled && config.exposeMappings.any { it.enabled }
hasWakeableTargets(config)    = (config.proxyEnabled && config.socksProxy.isNotBlank())
                                 || (config.forwardEnabled && config.forwardMappings.any { it.enabled })
```

- If `hasActiveExposedPorts(config)` is true: Power Save toggle is force-set
  to `false` (persisted) and disabled/greyed out in the UI, with a one-time
  info/snackbar ("Power Save is unavailable while exposed ports are
  enabled"). This is enforced in **both directions**:
  - Turning Power Save on while an exposed mapping is enabled → blocked.
  - Enabling/adding an exposed mapping (or the Expose section toggle) while
    Power Save is on → Power Save is auto-disabled + snackbar shown.
- If `!hasWakeableTargets(config)`: toggle remains enabled (so the user can
  turn it on ahead of configuring ports) but a hint text explains nothing
  will happen without at least one enabled forward mapping or SOCKS proxy.

### 4.2 Idle timer (node up → node down)

- While the node `isRunning` **and** `powerSaveEnabled`, a dedicated
  `idlePowerSaveMonitorJob` in `YggstackService` runs its **own** independent
  poll loop (separate from the Ports-screen `portStatsJob`, see §4.7),
  calling `yggstack?.getListenersJSON()` directly on a fixed 1s cadence,
  and computes `activeTransitConnections = sum(activeConnections for section in {proxy, forward})`
  on every tick.
- This loop only exists while Power Save is enabled; the Ports screen's own
  live view is unaffected and always refreshes at a fixed 1s (§4.7).
- A single idle-countdown job:
  - Starts / resets to `idleTimeoutSeconds` whenever
    `activeTransitConnections` transitions to `> 0`, or immediately after
    node start if it begins at `0`.
  - Is cancelled (no idle countdown) whenever `activeTransitConnections > 0`.
  - When it reaches zero **and** `powerSaveEnabled && !hasActiveExposedPorts`,
    triggers **idle shutdown** (§4.4).
- Countdown remaining time is exposed as a `StateFlow<Long?>` (seconds, or
  `null` when not counting down / feature disabled) for the Ports-screen
  card.
- Note: idle detection resolution is bounded by the port-stats poll interval
  — e.g. with a 10s poll interval, a connection that opens and closes
  between two polls may not be observed. This is called out in the UI hint
  text for the interval control (§6.1). This interval is a Power-Save-only
  setting; it has no effect on the Ports screen's own refresh rate.

### 4.3 Wake-on-connection

Before/while stopping the Go node, the service binds one placeholder listener
per **enabled** wakeable target:
- One per enabled `ForwardMapping` (TCP → `ServerSocket`, UDP →
  `DatagramSocket`) on `localIp:localPort`.
- One for the SOCKS proxy address, if `proxyEnabled`.

Placeholder behavior on activity:
- **TCP**: `ServerSocket.accept()` returns a socket → immediately close it
  (RST/FIN, client sees connection dropped) and trigger wake-up.
- **UDP**: first datagram received → the packet is intentionally dropped (UDP
  has no delivery guarantee, so this is within normal protocol semantics) and
  wake-up is triggered.

This is the deliberately simple option (vs. holding the connection open and
manually relaying once the real mapping is ready): it avoids re-implementing
any part of the Go forwarder in Kotlin, works uniformly for TCP/UDP, and has
no risk of holding stale sockets/timeouts. The trade-off — the very first
connection attempt during wake always fails and must be retried by the
client or user — is accepted. Most client software (browsers, SSH, curl with
retry, proxy-aware apps) either retries automatically or makes retrying a
one-click action for the user; this will be called out in the in-app hint
text.

Only the **first** trigger per wake cycle matters — as soon as any placeholder
fires, all placeholders for that cycle are torn down together and node
start-up begins (a burst of near-simultaneous connection attempts on
different ports only triggers one wake-up, not one per socket).

### 4.4 Idle shutdown sequence

1. Idle countdown reaches 0 (§4.2), and eligibility still holds.
2. Log the transition (persistent logger): `"Power Save: no active
   connections for {timeout}s — powering down node"`.
3. Bind all placeholder listeners (§4.3) **before** releasing the real ports,
   to minimize the gap where nothing is listening. (A short gap — releasing
   the Go-owned socket, then binding the Kotlin one — is unavoidable; a
   connection attempt landing in that ~tens-of-ms window sees a normal
   connection-refused, same as any brief service restart today.)
4. Run the existing stop logic (`yggstack.stop()`, clear IP/peer/stat state,
   cancel network callback, release wake/wifi/multicast locks) **but skip**
   `stopForeground()` / `stopSelf()`. The Android service, its process, and
   its notification remain alive.
5. Set new state: `_isPowerSaveIdle.value = true` (see §5.1). Update the
   foreground notification (§4.6).
6. Network-change callbacks, crash-restart-on-crash logic, and peer-cache
   updates are all no-ops while `_isPowerSaveIdle` is true (they only apply
   to a running node).

### 4.5 Wake-up sequence

Triggered by: a placeholder listener firing, the notification's "Wake Now"
action, or the in-app "Wake Now" button.

1. Unbind/close all placeholder listeners.
2. Call the existing `startYggstack(lastConfig)` path unchanged — same
   config, same peers, same mappings.
3. Set `_isPowerSaveIdle.value = false`.
4. Idle countdown (§4.2) begins fresh once the node reports running (natural
   consequence of `activeTransitConnections` starting at 0).
5. Log the transition: `"Power Save: waking node ({reason})"`.

If wake-up fails (existing error path in `startYggstack`), the service falls
back to the normal `Error` / stopped state — it does **not** silently retry
into another idle cycle, so the user notices and can investigate.

### 4.6 Foreground notification

- While `_isPowerSaveIdle`: text becomes e.g. *"Power saving — waiting for
  connections"*, ongoing (non-dismissible), with two actions:
  - **Wake Now** → triggers §4.5 immediately.
  - **Stop** → full stop (existing `ACTION_STOP`, tears down placeholders
    too, actually calls `stopSelf()`).
- **Small icon swap**: `createNotification()` gets a new `iconRes: Int`
  parameter (default `R.drawable.ic_qs_tile`, the current icon). While
  `_isPowerSaveIdle` is true, notifications are built/updated with a new
  `R.drawable.ic_power_save_idle` vector drawable (converted from the
  supplied `stack_group` Material Symbol asset). As soon as the node wakes
  back up (§4.5, before the "Connected" notification is posted), the icon
  reverts to `R.drawable.ic_qs_tile`. No other visual style changes.
- While running with Power Save enabled and idle-counting-down: no
  notification text/icon change is required (avoid noisy notification
  updates every second); the existing "Connected" notification/icon is
  enough. (Countdown is visible in-app on the Ports screen instead.)

### 4.7 Separating peer-details polling from port-stats polling

Today `startPeerStatsSubscriptionMonitor()` + `startPeerStatsUpdater()` is one
combined job driven by `_peerDetailsJSON.subscriptionCount +
_portStatsJSON.subscriptionCount`, fetching both peer info and port stats on
the same fixed 1s tick. This is split into **three** independent jobs:

- **`peerDetailsJob`** (Peers screen): unchanged behavior — fetches
  `getPeersJSON()`, Yggdrasil IP, public key, updates peer counts and the
  "Connected"/peer-count notification text. Still triggered solely by
  `_peerDetailsJSON.subscriptionCount > 0` (i.e. only while the Peers tab is
  open), still on a fixed 1s cadence.
- **`portStatsJob`** (Ports screen only): fetches `getListenersJSON()` and
  emits to `_portStatsJSON`. Still triggered solely by
  `_portStatsJSON.subscriptionCount > 0` (i.e. only while the Ports tab is
  open), **still on a fixed 1s cadence, unchanged** — the new poll-interval
  setting does not affect this job.
- **`idlePowerSaveMonitorJob`** (Power Save idle detection only, §4.2): a
  separate poll loop that only runs while `isRunning && powerSaveEnabled`,
  making its own direct `getListenersJSON()` calls (not shared with
  `portStatsJob`) at a fixed 1s cadence — there's nothing to poll once the
  node is idle, so this isn't user-configurable.
  It does not emit to `_portStatsJSON` — it only feeds the idle-countdown
  logic and the `idleCountdownSeconds`/`isPowerSaveIdle` state exposed to the
  UI. Kept deliberately independent (rather than reusing `portStatsJob`'s
  emissions) so the Ports screen's cadence is never influenced by the Power
  Save setting, and vice versa. Minor trade-off: while both the Ports tab is
  open and Power Save is enabled, `getListenersJSON()` is polled by two
  separate loops at the same 1s rate — an acceptable duplication given it's
  a cheap JNI call.
- All three jobs respect `_isRunning`; all three stop the same way
  (cancelled) during a full stop or an idle shutdown
  (`idlePowerSaveMonitorJob` keeps running through the idle-shutdown
  transition itself, since it's what detects when to trigger it — it only
  stops when the feature is disabled or the service fully stops).

### 4.8 Wake lock handling during idle

Goal: while `_isPowerSaveIdle` is true, **only the foreground service itself
remains** — zero wake locks of any kind held.

- `wifiLock` / `multicastLock`: no change needed beyond what idle shutdown
  (§4.4) already does — they're released as part of the existing stop logic,
  and re-acquired in `startYggstack()` on wake-up (§4.5) exactly as on a
  normal manual start.
- `wakeLock`: **scope changes** from "whole service lifetime" to "whole
  running-node lifetime", matching the other two locks:
  - `acquireWakeLock()` call moves out of `onCreate()` and into
    `startYggstack()` (alongside the existing `acquireWifiLock()` /
    `acquireMulticastLock()` calls).
  - `releaseWakeLock()` is called both in the normal `stopYggstack()` path
    and in the idle-shutdown path (§4.4 step 4).
  - `onDestroy()` keeps its existing `releaseWakeLock()` call as a safety net
    (no-op if already released).
  - Net behavior change: previously the partial wake lock was held from the
    moment the service process was created until the service was fully
    destroyed (in practice: almost the entire time the app/service existed,
    since `stopYggstack()` used to always call `stopSelf()`). Now it is held
    only while the Go node is actually running, which is a strictly smaller
    window even outside of Power Save — a beneficial side effect for all
    users, not just Power Save users.
- Caveat (documented, accepted): without a partial wake lock, the CPU can
  fully sleep while idle. A blocking `accept()`/datagram receive on the
  placeholder listener socket (§4.3) still completes once the kernel
  receives the packet — network interrupts wake the CPU independently of
  app-held wake locks — and the process itself stays alive because the
  foreground service (with its notification) keeps it out of the
  cached/killable state. Extreme OEM battery-optimization modes could still
  delay this on some devices; no different from any other Android
  foreground-service networking app today.

## 5. State model changes

### 5.1 `YggstackService`

New members:
```kotlin
private val _isPowerSaveIdle = MutableStateFlow(false)
val isPowerSaveIdle: StateFlow<Boolean> = _isPowerSaveIdle.asStateFlow()

private val _idleCountdownSeconds = MutableStateFlow<Long?>(null) // null = not counting down
val idleCountdownSeconds: StateFlow<Long?> = _idleCountdownSeconds.asStateFlow()

fun wakeNow() { ... }          // manual wake, callable from ViewModel + notification action
```
`isRunning` keeps its current meaning (Go node actually up); it is `false`
during idle power-save, same as a full stop, so any code that isn't
power-save-aware still degrades sanely (e.g. shows "stopped"-like state)
until updated.

### 5.2 `ServiceState` (data/ServiceState.kt)

Add a new case so the UI can distinguish "fully stopped" from "idling,
listening for wake":
```kotlin
sealed class ServiceState {
    object Stopped : ServiceState()
    object Starting : ServiceState()
    object Running : ServiceState()
    object PowerSaving : ServiceState()   // NEW
    object Stopping : ServiceState()
    data class Error(val message: String) : ServiceState()
}
```
`ConfigurationViewModel` derives this from `isRunning` + `isPowerSaveIdle`.

### 5.3 `YggstackConfig` / `ConfigRepository` — new fields

```kotlin
val powerSaveEnabled: Boolean = false
val powerSaveIdleTimeoutSeconds: Int = 15 // 15s default; range 10–120
```
New DataStore keys: `POWER_SAVE_ENABLED` (bool), `POWER_SAVE_IDLE_TIMEOUT`
(int, seconds), following the exact existing pattern used for
`MAX_BACKOFF_ENABLED` / `MAX_BACKOFF`.

The idle-detection poll cadence (`idlePowerSaveMonitorJob`, §4.7) is fixed at
1s and not user-configurable — the Ports screen's own live view also always
refreshes at a fixed 1s, unaffected by whether Power Save is enabled.

Included in TOML backup/export (`BackupConfig.kt`) alongside other toggles,
for consistency (open item — confirm during implementation whether power
save should be part of shareable backups, since it's a local
power-management preference rather than a network config; leaning **yes**,
same section as autostart-like toggles, but keep it out of the "Config" tab
diagnostic display of the *Yggdrasil* JSON, since it's Android-only).

## 6. UI design

### 6.1 Configuration screen — new "Power Save" card

Position: directly **above** the existing Log Level card, following the
`ConfigSectionWithToggle` visual pattern already used for Proxy / Expose /
Forward sections.

```
┌───────────────────────────────────────────┐
│ Power Save                         [ ⏻ ]   │  <- title + master Switch
│ Idle shutdown timeout:              5 min ▸│  <- opens a slider dialog
│ Port stats poll interval:              1s ▸│  <- opens a stepper/slider dialog
│                                             │
│ (hint, shown when disabled by exposed port)│
│ "Unavailable while an exposed port is      │
│  enabled."                                 │
└───────────────────────────────────────────┘
```

- Master `Switch`, disabled (with the hint text) when
  `hasActiveExposedPorts(config)` is true.
- Timeout row and poll-interval row are both only visible when the master
  toggle is on (both are meaningless with Power Save off).
- Timeout row opens a dialog reusing the `MaxBackoffDialog` slider pattern
  (`Slider(valueRange = 30f..1800f)`, shown formatted as `Xs`/`Ym Zs`),
  confirm/cancel buttons.
- Poll interval row opens a similar slider dialog
  (`Slider(valueRange = 1f..10f, steps = 8)`, shown as `Xs`), with a hint
  line clarifying it only controls how often Power Save checks for idle
  connections (not the Ports screen, which always refreshes every 1s).
- Optional secondary line under the toggle (always visible, muted style):
  short one-line explanation of the feature for first-time users, e.g. "Turns
  off the node after this much idle time; reconnecting on the next request."

### 6.2 Ports screen — new status card (under "View" card)

Shown only when `config.powerSaveEnabled == true` (hidden entirely
otherwise, to avoid clutter for users who don't use the feature):

- **Running + counting down**:
  `"Powering down in 04:32"` + small progress indicator (e.g. a linear
  progress bar counting down, or just the mm:ss text — keep it simple, no
  need for a full circular timer).
- **Running + connections active** (countdown not running):
  `"Power Save armed — active connections in progress"` (no timer digits).
- **Idle / node down**:
  `"Power saving — listening for connections"` + elapsed idle duration
  (`"Idle for 12m"`) + a **Wake Now** button inline in the card.

```
┌───────────────────────────────────────────┐
│ ⏻ Power Save                               │
│ Powering down in 04:32                     │
└───────────────────────────────────────────┘
        ‑‑‑ or, once idle ‑‑‑
┌───────────────────────────────────────────┐
│ ⏻ Power Save                               │
│ Idle for 12m — listening for connections   │
│                                [Wake Now]  │
└───────────────────────────────────────────┘
```

### 6.3 Start/Stop button & status text (Configuration screen)

- `ServiceState.PowerSaving` renders like a distinct third state: button
  still reads "Stop" (fully stops everything, including placeholders), and
  the status label reads "Power saving" instead of "Running"/"Stopped".
- No change to `Starting`/`Stopping`/`Error` handling.

## 7. Edge cases & interactions

| Scenario | Behavior |
|---|---|
| User manually taps "Stop" while idle-powered-down | Full stop: tear down placeholder listeners, `stopForeground` + `stopSelf`, same as stopping a normally-running node today. |
| App/service process killed by OS while idle | Placeholder listeners die with the process; nothing is listening until user reopens app / autostart triggers. Same limitation as the app being killed while fully stopped today — acceptable, no special handling. |
| Autostart on boot | Node fully starts as today; Power Save's idle timer then behaves normally from that point (does not start "already idle"). |
| Network change (WiFi ↔ cellular) while idle | Ignored — no reconnect logic runs while `_isPowerSaveIdle`; only an actual local connection attempt or manual Wake Now brings the node up (which will then pick current network normally). |
| Config changed (e.g. forward mapping edited) while idle | Placeholder listener set is rebuilt to match the new enabled mappings/socks state on save, without waking the node. |
| Power Save toggled off while idle | Node is woken immediately (can't stay "idle" with the feature off) and normal running state resumes. |
| Very short idle timeout (30s) with bursty traffic | Idle timer simply restarts every time `activeTransitConnections` becomes ≥1; if traffic is bursty with gaps <30s the node never powers down — expected. |
| Crash-restart-on-crash logic | Suspended while `_isPowerSaveIdle` (an intentional idle isn't a crash). |

## 8. Logging & observability

- Log every idle-shutdown and wake-up transition (reason: timeout / manual /
  TCP trigger on `addr:port` / UDP trigger on `addr:port`) via the existing
  `PersistentLogger`, at `info` level, so users can see it in the Logs tab.

## 9. Testing plan

- Unit-test `hasActiveExposedPorts` / `hasWakeableTargets` eligibility logic.
- Manual/instrumented checks:
  - Idle timeout triggers shutdown; placeholder listeners bound on the exact
    configured local ports.
  - TCP connect during idle wakes the node and the connection is refused
    (client sees connection reset), second attempt after wake succeeds.
  - UDP packet during idle wakes the node; datagram lost, later datagrams
    succeed.
  - Enabling an exposed mapping while Power Save is on auto-disables it +
    shows snackbar; toggle stays disabled while exposed mapping remains
    enabled.
  - Manual Wake Now (in-app button and notification action) wakes
    immediately.
  - Full Stop while idle correctly tears down everything (no orphaned
    placeholder sockets, no lingering notification).
  - Opening only the Peers tab does not start `portStatsJob`; opening only
    the Ports tab does not start `peerDetailsJob`; enabling Power Save starts
    `portStatsJob` even with both tabs closed.
  - Changing the port-stats poll interval (1–10s) only affects Power Save's
    idle-detection responsiveness; the Ports screen keeps refreshing every 1s
    regardless of this setting.
  - `adb shell dumpsys power` shows zero held wake locks for the app while
    `_isPowerSaveIdle` is true, and the `PARTIAL_WAKE_LOCK` reappears
    immediately on wake-up/manual start.
  - Notification small icon switches to `ic_power_save_idle` on idle
    shutdown and back to `ic_qs_tile` on wake-up, verified across the
    idle→wake→idle cycle (no stale icon left over from a previous state).

## 10. Open items for follow-up (not blocking v1)

- Consider surfacing a short one-time in-app explainer the first time the
  user turns Power Save on (tooltip/dialog) instead of just static hint text.
- Consider a "quiet hours" schedule (only power-save during certain times of
  day) — explicitly out of scope for v1.
- Consider per-mapping opt-out of power save (e.g. always keep one forward
  mapping hot) — explicitly out of scope for v1; feature is all-or-nothing
  across the "wakeable" set.
