# Low Power Mode - Product Requirements Document

**Version:** 1.0  
**Date:** December 25, 2025  
**Status:** Planning

---

## 1. Executive Summary

Low Power Mode is a battery-saving feature that automatically stops the Yggdrasil node when no active connections are detected for a configurable timeout period. When a new connection attempt is made to configured ports, the node automatically restarts and forwards the connection.

**Key Benefits:**
- Significant battery savings when network is idle
- Automatic resume on connection attempts
- Transparent to applications using the proxy
- User-configurable timeout values

---

## 2. Goals & Objectives

### Primary Goals
- Reduce battery consumption during idle periods
- Maintain seamless connectivity experience
- Support both SOCKS proxy and port forwarding use cases
- Provide clear visual feedback of power state

### Non-Goals
- Support for "Expose Local Port" feature (incompatible by design)
- Sub-second connection response times
- Guaranteed connection preservation during node restart
- Protocol-specific optimizations (HTTP, SSH, etc.)

### Success Metrics
- Battery life improvement: 20-40% during idle periods
- Connection establishment time: < 5 seconds from idle state
- User satisfaction with timeout options
- Zero false-positive disconnections

---

## 3. Technical Architecture

### 3.1 System States

```
┌─────────────────┐
│ Node Running    │ ← Normal operation
│ (Full Power)    │
└────────┬────────┘
         │ No traffic for timeout period
         ↓
┌─────────────────┐
│ Node Stopped    │ ← Low power state
│ (Proxy Active)  │ ← Kotlin proxy listening
└────────┬────────┘
         │ Connection attempt detected
         ↓
┌─────────────────┐
│ Node Starting   │ ← Transition state
│ (Holding Conn)  │ ← Connection queued
└────────┬────────┘
         │ Node ready
         ↓
┌─────────────────┐
│ Node Running    │ ← Connection forwarded
│ (Full Power)    │
└─────────────────┘
```

### 3.2 Components

#### **ConnectionMonitor (New - Kotlin)**
**Responsibility:** Track active connections and idle timeout

**Key Functions:**
- `trackConnection(connectionId: String, protocol: Protocol)` - Register new connection
- `updateActivity(connectionId: String)` - Update last activity timestamp
- `closeConnection(connectionId: String)` - Remove connection from tracking
- `getActiveConnectionCount(): Int` - Get current active connections
- `getIdleTimeSeconds(): Long` - Time since last activity
- `shouldStopNode(): Boolean` - Check if timeout exceeded

**State:**
```kotlin
data class TrackedConnection(
    val id: String,
    val protocol: Protocol,
    val createdAt: Long,
    val lastActivityAt: Long,
    var bytesReceived: Long = 0,
    var bytesSent: Long = 0
)
```

#### **ProxySocketServer (New - Kotlin)**
**Responsibility:** Accept connections while yggstack is stopped

**Key Functions:**
- `startListening(port: Int, protocol: Protocol)` - Bind to configured ports
- `stopListening()` - Release all bound ports
- `acceptConnection(): Socket` - Accept incoming connection
- `queueConnection(socket: Socket)` - Hold connection during startup
- `forwardQueuedConnections()` - Forward to yggstack after startup

**Supported Modes:**
- TCP: Accept and hold socket connection
- UDP: Buffer initial packets (up to MTU size)
- SOCKS5: Accept and hold, forward after auth

**Connection Holding:**
- Maximum hold time: 10 seconds
- Maximum queued connections: 10
- Behavior on overflow: Reject new connections with RST

#### **LowPowerModeManager (New - Kotlin)**
**Responsibility:** Coordinate low power mode operations

**Key Functions:**
- `enable(timeout: Duration)` - Enable low power mode
- `disable()` - Disable low power mode
- `checkIdleTimeout()` - Periodic check (every 10 seconds)
- `onConnectionDetected()` - Handle new connection while stopped
- `transitionToLowPower()` - Stop node, start proxy
- `transitionToFullPower()` - Stop proxy, start node

#### **Modified YggstackService**
**Changes Required:**
- Add low power mode state to service state machine
- Integrate ConnectionMonitor for traffic tracking
- Coordinate ProxySocketServer lifecycle
- Update notification icon based on power state
- Handle graceful transitions between states

---

## 4. Connection Tracking Strategy

### 4.1 Activity Definition

**Connection is ACTIVE if:**
- TCP: Open socket with data transfer in last N seconds
- UDP: Packets sent/received in last N seconds
- SOCKS: Active tunnel with data transfer in last N seconds

**Connection is INACTIVE if:**
- TCP: No data transfer for timeout period (even if socket open)
- UDP: No packets for timeout period
- SOCKS: Tunnel open but no data transfer for timeout period

### 4.2 Tracking Implementation

**For SOCKS Proxy:**
```kotlin
// Monitor at SOCKS server level in Go bindings
// Track actual data transfer through tunnel
// Expose activity via JNI callback
```

**For Forward Port Mappings:**
```kotlin
// Monitor in handleLocalTCPMapping / handleLocalUDPMapping
// Track bytes transferred in each direction
// Report activity to ConnectionMonitor
```

**For Remote Port Mappings (Exposed Ports):**
```
N/A - Feature disabled in low power mode
```

### 4.3 Traffic Monitoring

**Go-side changes:**
```go
// In yggstack.go - add activity callbacks
type ActivityCallback interface {
    OnConnectionCreated(connId string, protocol string)
    OnDataTransferred(connId string, bytesRx int64, bytesTx int64)
    OnConnectionClosed(connId string)
}

// Integrate into ProxyTCP and proxy UDP functions
func ProxyTCP(mtu uint16, c1, c2 net.Conn) {
    // ... existing code ...
    // Add: callback.OnDataTransferred(connId, bytesRx, bytesTx)
}
```

**Kotlin-side integration:**
```kotlin
class ActivityCallbackImpl : ActivityCallback {
    override fun onConnectionCreated(connId: String, protocol: String) {
        connectionMonitor.trackConnection(connId, Protocol.valueOf(protocol))
    }
    
    override fun onDataTransferred(connId: String, bytesRx: Long, bytesTx: Long) {
        connectionMonitor.updateActivity(connId)
    }
    
    override fun onConnectionClosed(connId: String) {
        connectionMonitor.closeConnection(connId)
    }
}
```

---

## 5. User Interface

### 5.1 Configuration Screen Changes

**New Section: Power Management**
```
┌─────────────────────────────────────────┐
│ Power Management                        │
├─────────────────────────────────────────┤
│                                         │
│ Low Power Mode              [OFF]      │ ← Toggle switch
│                                         │
│ When enabled, Yggdrasil will           │
│ automatically stop after no activity   │
│ for the selected timeout period.       │
│                                         │
│ Auto-stop after:                        │
│ [ 1m ] [ 2m* ] [ 3m ] [ 5m ]          │ ← Multi-choice buttons
│                                         │
│ * Default: 2 minutes                   │
│                                         │
│ ⚠️ Note: "Expose Local Port" must be   │
│   disabled to use Low Power Mode       │
│                                         │
└─────────────────────────────────────────┘
```

**Position:** After "Logs" section, before service control buttons

**Mutual Exclusion Logic:**
- When Low Power Mode is ON → Disable "Expose Local Port" section (grayed out)
- When "Expose Local Port" is ON → Disable "Low Power Mode" toggle (grayed out)
- Show tooltip explaining why when user tries to enable conflicting feature

### 5.2 Status Display

**In Configuration Screen (when Low Power Mode active):**
```
┌─────────────────────────────────────────┐
│ Status: Low Power Mode                  │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │
│ 🔋 Node stopped (waiting)               │
│ 🔌 Active connections: 0                │
│ ⏱️ Idle for: 0m 42s / 2m 00s            │
└─────────────────────────────────────────┘
```

**When node is running in low power mode:**
```
┌─────────────────────────────────────────┐
│ Status: Running                         │
│ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │
│ 🔋 Low Power Mode (active)              │
│ 🔌 Active connections: 3                │
│ ⏱️ Will stop in: 1m 18s (if idle)       │
└─────────────────────────────────────────┘
```

### 5.3 Notification Icons

**Two icon states required:**

1. **stack.svg** - Node running (normal state)
   - Used when yggstack is actively running
   - Default notification icon

2. **stack_off.svg** - Node stopped, waiting (low power state)
   - Used when node is stopped but proxy is listening
   - Indicates battery-saving mode active

**Icon Files Location:**
```
app/src/main/res/drawable/ic_stack.xml        (vector from stack.svg)
app/src/main/res/drawable/ic_stack_off.xml    (vector from stack_off.svg)
```

**Notification Update Logic:**
```kotlin
private fun updateNotification() {
    val icon = when {
        lowPowerModeManager.isInLowPowerState() -> R.drawable.ic_stack_off
        else -> R.drawable.ic_stack
    }
    
    val text = when {
        lowPowerModeManager.isInLowPowerState() -> 
            "Waiting for connections (Low Power)"
        else -> 
            "Connected: $yggdrasilIp"
    }
    
    // Update notification with new icon and text
}
```

---

## 6. Configuration Data Model

### 6.1 YggstackConfig Changes

**Add new fields:**
```kotlin
@Serializable
data class YggstackConfig(
    // ... existing fields ...
    
    // Low Power Mode settings
    val lowPowerModeEnabled: Boolean = false,
    val lowPowerTimeoutSeconds: Int = 120, // Default: 2 minutes
)
```

**Validation Rules:**
- `lowPowerTimeoutSeconds` must be one of: [60, 120, 180, 300]
- When `lowPowerModeEnabled = true`, must have `exposeEnabled = false`
- When `exposeEnabled = true`, must have `lowPowerModeEnabled = false`

### 6.2 Timeout Constants

```kotlin
object LowPowerModeConstants {
    val TIMEOUT_OPTIONS = listOf(
        60,    // 1 minute
        120,   // 2 minutes (default)
        180,   // 3 minutes
        300,   // 5 minutes
    )
    
    const val DEFAULT_TIMEOUT_SECONDS = 120
    const val MAX_QUEUED_CONNECTIONS = 10
    const val MAX_CONNECTION_HOLD_TIME_SECONDS = 10
    const val IDLE_CHECK_INTERVAL_SECONDS = 10
}
```

---

## 7. Implementation Plan

### Phase 1: Foundation (Days 1-2)
**Goal:** Core monitoring infrastructure

**Tasks:**
1. Create `ConnectionMonitor` class
   - Connection tracking data structures
   - Activity timestamp management
   - Idle timeout calculation
   
2. Add activity callbacks to Go bindings
   - Modify `yggstack.go` - add ActivityCallback interface
   - Integrate into ProxyTCP/ProxyUDP functions
   - Add JNI callback bridge to Kotlin

3. Create `TrackedConnection` data model
   - Connection metadata
   - Traffic statistics
   - Lifecycle management

**Deliverables:**
- [ ] ConnectionMonitor.kt (200 lines)
- [ ] Modified yggstack.go (+150 lines)
- [ ] JNI callback integration
- [ ] Unit tests for connection tracking

---

### Phase 2: Proxy Layer (Days 3-4)
**Goal:** Accept connections while node stopped

**Tasks:**
1. Create `ProxySocketServer` class
   - TCP server socket implementation
   - UDP socket implementation
   - Connection queue management
   
2. Implement connection holding logic
   - Queue incoming connections
   - Timeout handling (10s max)
   - Connection forwarding after startup

3. SOCKS proxy special handling
   - Accept SOCKS handshake
   - Hold after authentication
   - Forward to real SOCKS server after startup

**Deliverables:**
- [ ] ProxySocketServer.kt (350 lines)
- [ ] Connection queue implementation
- [ ] SOCKS handshake handling
- [ ] Integration tests

---

### Phase 3: Power State Management (Days 5-6)
**Goal:** Coordinate state transitions

**Tasks:**
1. Create `LowPowerModeManager` class
   - State machine implementation
   - Transition coordination
   - Error handling

2. Modify YggstackService
   - Integrate ConnectionMonitor
   - Add power state to service state
   - Coordinate ProxySocketServer lifecycle
   
3. Implement state transitions
   - Full Power → Low Power (stop node, start proxy)
   - Low Power → Full Power (stop proxy, start node, forward queued)
   - Handle race conditions

**Deliverables:**
- [ ] LowPowerModeManager.kt (400 lines)
- [ ] Modified YggstackService.kt (+300 lines)
- [ ] State machine tests
- [ ] Transition integration tests

---

### Phase 4: UI Implementation (Day 7)
**Goal:** User interface and configuration

**Tasks:**
1. Update YggstackConfig data model
   - Add lowPowerModeEnabled field
   - Add lowPowerTimeoutSeconds field
   - Add validation logic

2. Update ConfigurationScreen
   - Add Power Management section
   - Add toggle switch
   - Add timeout selector buttons
   - Implement mutual exclusion with Expose Ports

3. Add status display
   - Show connection count
   - Show idle timer/countdown
   - Update dynamically

4. Create notification icons
   - Convert stack.svg to XML drawable
   - Convert stack_off.svg to XML drawable
   - Integrate into notification updates

**Deliverables:**
- [ ] Modified YggstackConfig.kt (+10 lines)
- [ ] Modified ConfigurationScreen.kt (+150 lines)
- [ ] ic_stack.xml
- [ ] ic_stack_off.xml
- [ ] UI integration tests

---

### Phase 5: Testing & Polish (Days 8-9)
**Goal:** Comprehensive testing and bug fixes

**Tasks:**
1. **Functional Testing**
   - [ ] Basic enable/disable toggle
   - [ ] Timeout selection
   - [ ] Auto-stop after timeout
   - [ ] Auto-start on connection
   - [ ] Connection forwarding after startup
   - [ ] Multiple simultaneous connections
   - [ ] Rapid connect/disconnect cycles

2. **Edge Cases**
   - [ ] Connection arrives during node startup
   - [ ] Network change during low power mode
   - [ ] App backgrounded/foregrounded
   - [ ] Service killed by system
   - [ ] Race conditions in state transitions
   - [ ] Maximum queue overflow
   - [ ] Hold timeout expiration

3. **Integration Testing**
   - [ ] SOCKS proxy mode
   - [ ] Forward port mappings (TCP)
   - [ ] Forward port mappings (UDP)
   - [ ] Mixed TCP/UDP workloads
   - [ ] Long-running connections
   - [ ] High-frequency short connections

4. **Battery Testing**
   - [ ] Measure battery drain in low power state
   - [ ] Measure battery drain with frequent wakeups
   - [ ] Compare to always-on mode
   - [ ] Optimize wake lock usage

5. **UI/UX Testing**
   - [ ] Status updates are real-time
   - [ ] Icons change correctly
   - [ ] Mutual exclusion works
   - [ ] Error messages are clear
   - [ ] Settings persist correctly

**Deliverables:**
- [ ] Test report document
- [ ] Bug fixes
- [ ] Performance optimizations
- [ ] Documentation updates

---

### Phase 6: Documentation (Day 10)
**Goal:** User and developer documentation

**Tasks:**
1. Update README.md
   - Add Low Power Mode feature description
   - Add usage instructions
   - Add troubleshooting guide

2. Update PRD.md
   - Add Low Power Mode section
   - Update feature matrix
   - Update known limitations

3. Add inline code documentation
   - KDoc for all new classes
   - Comment complex logic
   - Add architecture diagrams

4. Create user guide
   - When to use low power mode
   - Recommended timeout values
   - Limitations and caveats

**Deliverables:**
- [ ] Updated README.md
- [ ] Updated PRD.md
- [ ] Code documentation
- [ ] User guide

---

## 8. Technical Specifications

### 8.1 Connection Lifecycle

**Normal Flow:**
```
1. Connection arrives at proxy socket
2. ProxySocketServer.acceptConnection()
3. ConnectionMonitor.trackConnection()
4. Data transfer occurs
5. ConnectionMonitor.updateActivity() on each transfer
6. Connection closes
7. ConnectionMonitor.closeConnection()
8. Idle timer starts if no other connections
9. After timeout: LowPowerModeManager.transitionToLowPower()
```

**Low Power Flow:**
```
1. Connection arrives at proxy socket (node stopped)
2. ProxySocketServer.queueConnection()
3. LowPowerModeManager.onConnectionDetected()
4. LowPowerModeManager.transitionToFullPower()
5. YggstackService.startYggstack()
6. Wait for node ready (~2-5 seconds)
7. ProxySocketServer.forwardQueuedConnections()
8. ConnectionMonitor.trackConnection()
9. Resume normal flow
```

### 8.2 Threading Model

**ConnectionMonitor:**
- Runs on shared service coroutine scope
- Updates are synchronized (Mutex)
- Idle check runs on fixed timer (every 10s)

**ProxySocketServer:**
- Each port has dedicated thread for accept loop
- Connection handlers use coroutine pool
- Queue operations are thread-safe (ConcurrentLinkedQueue)

**LowPowerModeManager:**
- Runs on service coroutine scope
- State transitions are atomic
- Coordinates with service lifecycle

### 8.3 Resource Management

**WakeLocks:**
- Acquire partial wake lock when queuing connection
- Release after connection forwarded or timeout
- Prevent system sleep during transition

**Network Locks:**
- Keep WiFi lock during transition if on WiFi
- Release when back in low power state
- Maintain during full power operation

**File Descriptors:**
- Properly close all sockets on transition
- Track open FDs to prevent leaks
- Limit maximum concurrent connections

### 8.4 Error Handling

**Connection Queue Overflow:**
```kotlin
if (queuedConnections.size >= MAX_QUEUED_CONNECTIONS) {
    logWarning("Connection queue full, rejecting new connection")
    socket.close()
    return
}
```

**Node Startup Failure:**
```kotlin
try {
    startYggstack()
} catch (e: Exception) {
    logError("Failed to start node in low power mode: ${e.message}")
    // Close all queued connections
    queuedConnections.forEach { it.close() }
    queuedConnections.clear()
    // Transition back to low power state
    transitionToLowPower()
}
```

**Timeout During Hold:**
```kotlin
if (System.currentTimeMillis() - queueTime > MAX_HOLD_TIME_MS) {
    logWarning("Connection hold timeout, closing connection")
    socket.close()
    queuedConnections.remove(socket)
}
```

---

## 9. Known Limitations

### 9.1 Design Limitations

1. **Exposed Ports Incompatible**
   - Cannot receive Yggdrasil connections while node stopped
   - Feature must be disabled when using low power mode
   - UI enforces mutual exclusion

2. **Startup Delay**
   - 2-5 second delay when resuming from low power
   - First connection may timeout in impatient clients
   - Subsequent connections are fast

3. **UDP Connection Tracking**
   - No true "connection" state for UDP
   - Any packet resets idle timer (including probes)
   - May keep node running longer than needed

4. **SOCKS Idle Detection**
   - Cannot detect application-level idle (e.g., browser open but not loading)
   - Tracks only data transfer through SOCKS tunnel
   - May stop node while browser is "idle" but ready to navigate

### 9.2 Android Platform Limitations

1. **Background Restrictions**
   - Android 12+ aggressive battery optimization
   - Doze mode may delay connection detection
   - Users must disable battery optimization for app

2. **Port Binding**
   - Ports must be > 1024 (unprivileged)
   - Cannot bind to already-used ports
   - Port conflicts with other apps possible

3. **Wake Lock Drain**
   - Holding wake lock during transition uses battery
   - Frequent transitions may negate power savings
   - Optimum timeout is workload-dependent

### 9.3 Implementation Limitations

1. **Connection Count Limit**
   - Maximum 10 queued connections
   - Overflow connections are rejected
   - No prioritization mechanism

2. **Protocol Agnostic**
   - No HTTP-specific optimizations
   - No SSH keepalive awareness
   - Cannot distinguish protocol types

3. **Single Timeout Value**
   - One timeout for all connection types
   - Cannot have different timeouts for SOCKS vs ports
   - May not be optimal for mixed workloads

---

## 10. Success Criteria

### 10.1 Functional Requirements

- [ ] Low power mode can be enabled/disabled via UI toggle
- [ ] Timeout can be selected from 4 predefined values (1m, 2m, 3m, 5m)
- [ ] Node automatically stops after timeout with no activity
- [ ] Node automatically starts on new connection attempt
- [ ] Connections are successfully forwarded after node startup
- [ ] Mutual exclusion with Expose Ports is enforced
- [ ] Notification icon changes based on power state
- [ ] Settings persist across app restarts

### 10.2 Performance Requirements

- [ ] Node startup from low power state: < 5 seconds (p95)
- [ ] Connection hold time: < 10 seconds
- [ ] Idle detection accuracy: ± 10 seconds
- [ ] Battery savings: > 20% during idle periods
- [ ] Connection success rate: > 95% (accounting for timeout failures)

### 10.3 Quality Requirements

- [ ] No crashes during state transitions
- [ ] No memory leaks from queued connections
- [ ] No file descriptor leaks
- [ ] Graceful handling of all error conditions
- [ ] Clear error messages to user
- [ ] Comprehensive logging for debugging

---

## 11. Future Enhancements

### Phase 2 Features (Post-Launch)

1. **Smart Timeout Adjustment**
   - Learn user patterns
   - Adjust timeout based on typical usage
   - Suggest optimal timeout value

2. **Per-Port Configuration**
   - Different timeouts for different ports
   - Protocol-aware timeout suggestions
   - Priority-based connection handling

3. **Advanced Statistics**
   - Battery savings estimate
   - Connection success rate
   - Average startup time
   - Network usage breakdown

4. **Connection Priorities**
   - High-priority ports (e.g., SSH) get queued first
   - Low-priority connections can be rejected on overflow
   - User-configurable priority list

5. **Protocol Awareness**
   - Detect SSH and preserve keepalives
   - HTTP/HTTPS fast path
   - Gaming/VoIP exclusion (never stop)

---

## 12. Testing Strategy

### 12.1 Unit Tests

**ConnectionMonitor Tests:**
- [ ] Track single connection
- [ ] Track multiple connections
- [ ] Update activity timestamp
- [ ] Close connection
- [ ] Calculate idle time
- [ ] Timeout detection

**ProxySocketServer Tests:**
- [ ] Bind to TCP port
- [ ] Bind to UDP port
- [ ] Accept TCP connection
- [ ] Receive UDP packet
- [ ] Queue connection
- [ ] Forward connection
- [ ] Handle timeout
- [ ] Handle overflow

**LowPowerModeManager Tests:**
- [ ] Enable low power mode
- [ ] Disable low power mode
- [ ] Transition to low power
- [ ] Transition to full power
- [ ] Handle connection while stopped
- [ ] Handle concurrent connections

### 12.2 Integration Tests

- [ ] End-to-end connection flow (SOCKS)
- [ ] End-to-end connection flow (TCP port forward)
- [ ] End-to-end connection flow (UDP port forward)
- [ ] State persistence across app restart
- [ ] Network change handling
- [ ] Battery optimization compatibility

### 12.3 Manual Testing Scenarios

**Scenario 1: Basic Flow**
1. Enable low power mode, 2-minute timeout
2. Start yggstack
3. Wait 2+ minutes with no traffic
4. Verify node stops, notification icon changes
5. Open connection through SOCKS proxy
6. Verify node starts, connection succeeds
7. Wait 2+ minutes
8. Verify node stops again

**Scenario 2: Rapid Connections**
1. Enable low power mode
2. Start node, wait for auto-stop
3. Make 5 connections in quick succession
4. Verify all connections succeed
5. Verify node stays running during activity
6. Verify node stops after all connections idle

**Scenario 3: Mutual Exclusion**
1. Enable "Expose Local Port"
2. Verify "Low Power Mode" toggle is disabled
3. Disable "Expose Local Port"
4. Enable "Low Power Mode"
5. Verify "Expose Local Port" section is disabled

**Scenario 4: Network Change**
1. Enable low power mode
2. Start node, wait for auto-stop
3. Switch WiFi off/on
4. Verify proxy server still listening
5. Make connection, verify node starts

---

## 13. Rollout Plan

### Alpha Release (Internal Testing)
- Duration: 1 week
- Testers: Development team
- Focus: Functionality and crash-free operation
- Success criteria: All core features work, no crashes

### Beta Release (External Testing)
- Duration: 2 weeks
- Testers: 10-20 volunteers
- Focus: Real-world usage patterns and battery impact
- Success criteria: Positive feedback, measurable battery savings

### Production Release
- Prerequisites: All tests pass, no critical bugs
- Release notes: Document feature and limitations
- User guidance: Tutorial on optimal timeout selection
- Monitoring: Track crash rates and user feedback

---

## 14. Risks & Mitigation

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| Frequent wakeups drain battery | High | Medium | Optimize idle check interval, use system alarms |
| Connection timeouts frustrate users | High | Medium | Clear documentation, suggested timeouts |
| Port binding conflicts | Medium | Low | Proper error handling, suggest alternate ports |
| State machine bugs cause crashes | High | Medium | Comprehensive testing, defensive coding |
| Android battery optimization kills service | High | High | Request exclusion, detect and warn user |
| UDP tracking too aggressive | Medium | Medium | Configurable UDP idle timeout (future) |

---

## 15. Dependencies

### Code Dependencies
- Kotlin Coroutines (existing)
- AndroidX Lifecycle (existing)
- yggstack.aar (modifications required)

### External Dependencies
- None (all functionality in-app)

### System Requirements
- Android 6.0+ (API 23+) - no change
- Battery optimization exclusion recommended

---

## 16. Metrics & Analytics

### Key Metrics to Track
1. **Adoption Rate:** % of users enabling low power mode
2. **Timeout Distribution:** Which timeout values are popular
3. **Battery Impact:** Before/after comparison
4. **Connection Success Rate:** % of connections that succeed from low power
5. **Startup Time:** p50, p95, p99 node startup latency
6. **State Transition Frequency:** Average transitions per hour
7. **Error Rate:** Failed startups, queue overflows, timeouts

### Logging Points
```kotlin
// Enable low power mode
analytics.logEvent("low_power_mode_enabled", mapOf("timeout" to timeoutSeconds))

// Node auto-stopped
analytics.logEvent("low_power_auto_stop", mapOf(
    "idle_duration" to idleSeconds,
    "last_connection_count" to connectionCount
))

// Node auto-started
analytics.logEvent("low_power_auto_start", mapOf(
    "queued_connections" to queueSize,
    "startup_time_ms" to startupTimeMs
))

// Connection forwarded
analytics.logEvent("low_power_connection_forwarded", mapOf(
    "hold_time_ms" to holdTimeMs,
    "success" to success
))

// Error occurred
analytics.logEvent("low_power_error", mapOf(
    "error_type" to errorType,
    "state" to currentState
))
```

---

## 17. Appendix

### A. Timeout Recommendations by Use Case

| Use Case | Recommended Timeout | Rationale |
|----------|-------------------|-----------|
| Web Browsing | 2 minutes | Short pages load quickly, medium pages need some idle time |
| SSH Sessions | 3 minutes | SSH keepalive often 60s, need buffer |
| Messaging Apps | 2 minutes | Frequent but bursty traffic |
| File Transfers | 5 minutes | Long transfers with potential pauses |
| P2P/BitTorrent | 5 minutes | Many connections, sporadic traffic |
| Remote Desktop | 3 minutes | Periods of inactivity during use |
| General Mixed Use | 2 minutes | Best all-around default |

### B. State Machine Diagram

```
                    ┌──────────────┐
                    │   STOPPED    │
                    └──────┬───────┘
                           │ Start service
                           ↓
    ┌─────────────┐  ┌─────────────────┐
    │   STOPPED   │←─│     STARTING    │
    │  (Low Pwr)  │  └────────┬────────┘
    └──────┬──────┘           │ Node ready
           │                  ↓
           │            ┌─────────────────┐
Connection │            │    RUNNING      │
detected   │            │  (Full Power)   │
           │            └────────┬────────┘
           │                     │ Idle timeout
           │    ┌────────────────┘
           │    │ Stop node
           ↓    ↓
    ┌─────────────────┐
    │    STOPPING     │
    │  (Transition)   │
    └────────┬────────┘
             │ Node stopped
             ↓
    ┌─────────────────┐
    │     STOPPED     │
    │   (Low Power)   │
    │   Proxy active  │
    └─────────────────┘
```

### C. File Structure

```
app/src/main/java/link/yggdrasil/yggstack/android/
├── service/
│   ├── YggstackService.kt (modified)
│   ├── ConnectionMonitor.kt (new)
│   ├── ProxySocketServer.kt (new)
│   └── LowPowerModeManager.kt (new)
├── data/
│   ├── YggstackConfig.kt (modified)
│   └── TrackedConnection.kt (new)
├── ui/
│   └── configuration/
│       ├── ConfigurationScreen.kt (modified)
│       └── ConfigurationViewModel.kt (modified)
└── res/
    └── drawable/
        ├── ic_stack.xml (new)
        └── ic_stack_off.xml (new)

lib/yggstack/mobile/
└── yggstack.go (modified +150 lines)
```

### D. API Summary

**New Public APIs:**

```kotlin
// ConnectionMonitor
class ConnectionMonitor {
    fun trackConnection(id: String, protocol: Protocol)
    fun updateActivity(id: String)
    fun closeConnection(id: String)
    fun getActiveConnectionCount(): Int
    fun getIdleTimeSeconds(): Long
    fun shouldStopNode(timeoutSeconds: Int): Boolean
}

// ProxySocketServer
class ProxySocketServer {
    fun startListening(ports: List<PortMapping>): Result<Unit>
    fun stopListening()
    fun getQueuedConnectionCount(): Int
    suspend fun forwardQueuedConnections()
}

// LowPowerModeManager
class LowPowerModeManager {
    fun enable(timeoutSeconds: Int)
    fun disable()
    fun isEnabled(): Boolean
    fun isInLowPowerState(): Boolean
    fun getActiveConnectionCount(): Int
    fun getIdleTimeSeconds(): Long
}
```

**Modified APIs:**

```kotlin
// YggstackConfig
data class YggstackConfig(
    // ... existing fields ...
    val lowPowerModeEnabled: Boolean = false,
    val lowPowerTimeoutSeconds: Int = 120,
)

// YggstackService
class YggstackService {
    // ... existing methods ...
    val lowPowerModeManager: LowPowerModeManager
    val connectionMonitor: ConnectionMonitor
}
```

---

## 18. Glossary

- **Low Power Mode:** Feature that automatically stops Yggdrasil node when idle
- **Idle Timeout:** Configurable period of inactivity before auto-stop
- **Proxy Server:** Kotlin component that accepts connections while node stopped
- **Connection Holding:** Temporary queuing of connections during node startup
- **Activity:** Data transfer through tracked connection
- **Full Power State:** Normal operation, Yggdrasil node running
- **Low Power State:** Battery-saving mode, node stopped, proxy listening
- **Transition State:** Intermediate state during node start/stop

---

**Document Version History:**
- v1.0 (2025-12-25): Initial PRD based on requirements analysis

**Review & Approval:**
- [ ] Technical Lead
- [ ] Product Owner  
- [ ] QA Lead

**Next Steps:**
1. Review and approve PRD
2. Create detailed task breakdown
3. Assign development resources
4. Begin Phase 1 implementation
