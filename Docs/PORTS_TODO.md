# Ports Stats — Follow-ups

The `bulkBarrierPreWrite` crash investigation is closed (see git history /
PR description for the full story if needed). Root cause: `LogWriter` was
shared, unsynchronized, across 4 independent per-subsystem `*log.Logger`
instances, allowing concurrent JNI `onLog` upcalls. Fixed with a mutex in
`lib/yggstack/mobile/yggstack.go` `LogWriter.Write`. The GC-off workaround
(`mobile/diag.go`) and the on-device stress harness (`stress_local_test.go`)
have been removed; the app now runs with normal GC.

## Queued work (approved, not yet built)

- **UDP session idle expiry (~60s).** UDP "connections" in the listener
  stats registry (`lib/yggstack/mobile/stats.go`) are distinct client
  endpoints that never expire — Active only drops on service stop / mapping
  toggle, and the underlying session map leaks slowly. Add a per-session
  last-seen timestamp + periodic sweep in both UDP handlers
  (`handleLocalUDPMappingCtx` / `handleRemoteUDPMappingCtx` in
  `mobile/yggstack.go`); on expiry, decrement Active and close the
  tunnel-side conn.
- **Ports page rate polish.** Age out stale rates (show `—` if the last
  snapshot is older than ~3s) and smooth the rate with a sliding window, in
  `DiagnosticsScreen.kt` `PortsViewer`.

## Open questions

- Keep `listenerStatsWrappingEnabled` (in `mobile/yggstack.go`) as a runtime
  toggle, or fold it back to an always-on constant now that the crash is
  fixed?
- Should per-listener stats also cover the SOCKS DNS resolver traffic
  (currently only proxied payload bytes are counted)?

## Fragility notes (do not regress)

- `gologme/log`'s `Output()` reads `l.levels[level]` and
  `EnableLevel`/`DisableLevel` write it — both outside the logger's mutex
  (confirmed in `github.com/gologme/log@v1.3.0/log.go`). Currently safe only
  because `Yggstack.SetLogLevel` is called exactly once, synchronously,
  before `Start()`. Do not add a code path that calls `SetLogLevel` (or
  anything hitting `applyLogLevel`) after `Start()` without adding locking.
- Stats are counted on the Yggdrasil-facing leg: RX = from network, TX = to
  network; a UDP endpoint = one connection; TCP "close" fires once via a
  guarded wrapper hook.
