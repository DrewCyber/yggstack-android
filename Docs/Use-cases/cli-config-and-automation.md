# CLI: apply a config and control the service

Provision the app from a shell (`adb`, Tasker, Termux, CI) using the backup
file exported from the app (Diagnostics → Config → backup button). The
`config` intent extra accepts either:

- **inline content** — the backup text itself (TOML), or
- **an absolute path to a file** containing it. The app runs unprivileged,
  so it can only open paths it has access to: `/data/local/tmp` works
  (adb push + chmod 644); arbitrary `/sdcard` paths — including
  **Download** — are blocked by scoped storage. For those, pass the content
  inline instead (the adb shell user *can* read them).

## Getting the backup to the command

From a file on the computer:

```bash
adb shell am start -n link.yggdrasil.yggstack.android/.automation.AutomationStartActivity \
    --es config "$(cat yggstack_backup.toml)"
```

From the device's Download folder (shell reads it, app can't):

```bash
adb shell am start -n link.yggdrasil.yggstack.android/.automation.AutomationStartActivity \
    --es config "$(adb shell cat /sdcard/Download/yggstack_backup.toml)"
```

Or stage a file for the path form in `/data/local/tmp`:

```bash
adb push yggstack_backup.toml /data/local/tmp/
adb shell chmod 644 /data/local/tmp/yggstack_backup.toml
adb shell am start -n link.yggdrasil.yggstack.android/.automation.AutomationStartActivity \
    --es config /data/local/tmp/yggstack_backup.toml
```

## Commands

Package below is the go flavor; for the NG flavor replace it with
`link.yggdrasil.yggstack.android.ng` (class names stay the same).

### Apply a config and open the app

```bash
adb shell am start -n link.yggdrasil.yggstack.android/.MainActivity \
    --es config /data/local/tmp/yggstack_backup.toml
```

Applies the backup exactly like the in-app Restore flow (parse → validate →
merge onto the current config), then the app opens. A toast confirms
"Configuration imported successfully" or shows the error. The apply happens
before the service-on-app-start policy reads the config, so if your
Settings have the service starting on app open, it starts with the new
config. The extra is applied once per launch — rotation/recreation does not
re-apply it.

### Apply a config and start the service (one shot)

```bash
adb shell am start -n link.yggdrasil.yggstack.android/.automation.AutomationStartActivity \
    --es config /data/local/tmp/yggstack_backup.toml
```

Transparent, no-UI activity: applies the backup, then starts the service
with it. If the backup fails to parse or validate, the start is aborted
(with a toast and an `AutomationStartActivity` log line) instead of
launching with a stale config.

### Start / stop the service with the current config

```bash
adb shell am start -n link.yggdrasil.yggstack.android/.automation.AutomationStartActivity
adb shell am start -n link.yggdrasil.yggstack.android/.automation.AutomationStopActivity
```

The start activity reads the current config from storage at invocation, so
run it after a config apply to start with the freshly imported settings.
These are the same exported activities behind the app's launcher shortcuts.

## On-device automation (no computer)

### Tasker

Tasker can read Download itself (the app cannot), so feed the backup
content inline. Two actions:

1. **File → Read File** — `/sdcard/Download/yggstack_backup.toml` into a
   local variable, e.g. `%backup`.
2. **System → Send Intent**:
   - Target: **Activity**
   - Package: `link.yggdrasil.yggstack.android`
     (NG flavor: `link.yggdrasil.yggstack.android.ng`)
   - Class: `link.yggdrasil.yggstack.android.automation.AutomationStartActivity`
   - Extra (one line): `config:%backup`

That applies the backup and starts the service in one shot; a toast
confirms the result. To just stop the service, send the same intent to
`...automation.AutomationStopActivity` with no extras.

### Termux

```bash
pkg install termux-am   # once
am start -n link.yggdrasil.yggstack.android/.automation.AutomationStartActivity \
  --es config "$(cat ~/yggstack_backup.toml)"
```

The file can live anywhere Termux can read — its own home included —
because only the content is passed, never a path the app would have to
open.

## Behavior notes

- The backup format and validation are identical to the in-app
  Config → restore flow; nothing CLI-specific is accepted.
- Applying a config never restarts a **running** service — the node keeps
  its current config until the next start (same as the in-app restore).
  Automation sequence for a running node: apply →
  `AutomationStopActivity` → `AutomationStartActivity`.
- A backup without the Yggdrasil section (proxy/mappings only) merges onto
  the existing identity and peers; a full backup replaces them.
