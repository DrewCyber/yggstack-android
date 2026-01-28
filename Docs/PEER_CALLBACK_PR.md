# Peer Change Callback Feature

## Overview

This PR adds a callback mechanism to notify applications when peer connection states change in yggdrasil-go. This allows applications to react to peer connect/disconnect events in real-time without polling.

## Motivation

Applications using yggdrasil-go often need to know when peers connect or disconnect to update UI, adjust behavior, or trigger specific actions. Previously, this required:
- Periodic polling of `GetPeers()`
- Manual tracking of peer state changes
- No immediate notification of connection state changes

This callback system provides an event-driven approach that is more efficient and responsive.

## Changes

### Core API Changes

1. **New `PeerChangeCallback` type** (`src/core/types.go`):
```go
type PeerChangeCallback func(connected int, total int)
```
- `connected`: Number of currently connected (Up) peers
- `total`: Total number of configured peers

2. **New method `SetPeerChangeCallback`** (`src/core/core.go`):
```go
func (c *Core) SetPeerChangeCallback(callback PeerChangeCallback)
```
- Thread-safe method to register a callback
- Pass `nil` to remove the callback
- Callback is invoked in a separate goroutine to avoid blocking

3. **Callback triggers** (`src/core/link.go`):
- When a peer is added
- When a peer connection is established
- When a peer connection closes
- When a peer is removed

### Testing

Added comprehensive tests in `src/core/peer_callback_test.go`:
- Basic callback functionality
- Nil callback handling (no crash)
- Multiple rapid state changes
- Connection/disconnection cycle

## Usage Example

### Basic Usage

```go
package main

import (
    "fmt"
    "github.com/yggdrasil-network/yggdrasil-go/src/core"
)

func main() {
    // Create core instance
    c, err := core.New(cert, logger, options...)
    if err != nil {
        panic(err)
    }
    defer c.Stop()
    
    // Register callback
    c.SetPeerChangeCallback(func(connected, total int) {
        fmt.Printf("Peer state changed: %d/%d connected\n", connected, total)
        
        if connected == 0 {
            fmt.Println("Warning: No peers connected!")
        }
    })
    
    // Add peers, etc.
    // Callback will be triggered automatically on state changes
}
```

### Android/Mobile Example

```go
package mobile

import (
    "github.com/yggdrasil-network/yggdrasil-go/src/core"
)

type PeerChangeHandler interface {
    OnPeerCountChanged(connected, total int)
}

func (m *Yggdrasil) SetPeerChangeHandler(handler PeerChangeHandler) {
    if handler != nil {
        m.core.SetPeerChangeCallback(func(connected, total int) {
            handler.OnPeerCountChanged(connected, total)
        })
    } else {
        m.core.SetPeerChangeCallback(nil)
    }
}
```

### Use Cases

1. **UI Updates**: Update connection indicators in real-time
2. **Network Monitoring**: Track connectivity health
3. **Auto-reconnection**: Trigger reconnection attempts when all peers disconnect
4. **Metrics/Logging**: Record peer state changes for analytics
5. **Application Logic**: Adjust behavior based on peer availability

## Backward Compatibility

This change is **fully backward compatible**:
- No changes to existing APIs
- New functionality is opt-in
- No performance impact if callback is not used
- Existing code continues to work without modification

## Performance Considerations

- Callback is invoked in a separate goroutine (non-blocking)
- No overhead if callback is not registered
- Minimal overhead when callback is registered
- Callback should be lightweight to avoid delaying peer operations

## Future Enhancements

Possible future additions (not in this PR):
- Detailed peer change information (which peer, direction, reason)
- Different callbacks for different event types
- Filtering options (only notify for specific peers)

## Testing Instructions

```bash
cd src/core
go test -v -run TestPeerChange
```

## Related Issues

This feature enables applications to implement reactive UIs and better user experiences by providing immediate feedback on network connectivity changes.
