# Product Requirements Document (PRD)
# Yggstack Android Application

**Target Platform:** Android 6.0+ (API 23+)

---

## 1. Executive Summary

Yggstack Android is a native Kotlin application that provides a user-friendly interface for configuring and running yggstack on Android devices. The key differentiator is that it operates **without using VPN Service API**, running yggstack as a background service instead. The application enables users to connect to the Yggdrasil network, configure SOCKS proxy, DNS resolver, and manage port forwarding.

---

## 2. Goals & Objectives

### Primary Goals
- Provide intuitive UI for yggstack configuration
- Enable background execution of yggstack without VPN API
- Support peer management and connection monitoring
- Implement port forwarding (local and remote)
- Automated APK builds via GitHub Actions

### Success Metrics
- Successful compilation of the application
- Stable background service execution
- Correct integration with yggstack.aar library
- Automated CI/CD pipeline for releases

---

## 3. Target Audience

- Yggdrasil network users on Android devices
- Privacy-conscious users seeking decentralized networking
- Developers testing Yggdrasil applications
- Users requiring port forwarding through Yggdrasil

---

## 4. Technical Requirements

### 4.1 Platform Requirements
- **Minimum SDK:** Android 6.0 (API 23)
- **Target SDK:** Android 14 (API 34)
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose with Material Design 3
- **Architecture:** MVVM (Model-View-ViewModel)

### 4.2 Dependencies
- Jetpack Compose for UI
- AndroidX libraries (Core, Lifecycle, ViewModel)
- Material Design 3 components
- Kotlin Coroutines for async operations
- DataStore for preferences storage
- yggstack.aar (pre-built mobile bindings)

### 4.3 Build System
- Gradle with Kotlin DSL
- GitHub Actions for automated builds
- Self-signed certificate for APK signing

---

## 5. Development Phases

### Phase 1: Basic UI and Build Setup ✓
**Objective:** Create functional UI, ensure successful compilation, and set up CI/CD

#### Deliverables:
1. **Project Structure**
   - Android Studio project with proper package structure
   - Integration of yggstack.aar library
   - Gradle configuration for dependencies

2. **User Interface**
   - Bottom Navigation with main screens:
     - Configuration (Home)
     - Diagnostics
     - Settings
   - Material Design 3 theme implementation
   - Light/Dark theme support with manual override

3. **Configuration Screen Components:**
   - Peers list (editable, add/remove)
   - Private key input field (masked text)
   - Yggdrasil IP display (read-only, with copy button)
   - Proxy Configuration section (collapsible, with enable/disable toggle):
     - SOCKS proxy address input
     - DNS server address input
   - Expose Local Port section (collapsible, with enable/disable toggle)
   - Forward Remote Port section (collapsible, with enable/disable toggle)
   - Start/Stop button (disables editing when running)

4. **Data Persistence**
   - SharedPreferences/DataStore for configuration
   - Save/restore all settings between app restarts
   - Auto-generate config on first launch

5. **GitHub Actions CI/CD**
   - Workflow for building debug APK on tag push (x.x.x format)
   - Self-signed APK signing
   - Automatic upload to GitHub Releases

#### Acceptance Criteria:
- [ ] App compiles without errors
- [ ] All UI screens are navigable
- [ ] Configuration persists between launches
- [ ] GitHub Actions successfully builds and publishes APK for version tags

---

### Phase 2: Yggdrasil Core Functionality
**Objective:** Implement Yggdrasil connection, peer management, and monitoring

#### Deliverables:

1. **Mobile Bindings Enhancement**
   - Extend `lib/yggstack/mobile/yggstack.go` with missing methods:
     ```go
     // Port mapping management
     AddLocalTCPMapping(localAddr, remoteAddr string) error
     AddLocalUDPMapping(localAddr, remoteAddr string) error
     AddRemoteTCPMapping(remotePort int, localAddr string) error
     AddRemoteUDPMapping(remotePort int, localAddr string) error
     ClearLocalMappings() error
     ClearRemoteMappings() error
     
     // Peer status
     GetPeerStatus() (string, error) // Returns JSON with peer info
     
     // Configuration export
     GetCurrentConfig() (string, error) // Returns current config as JSON
     
     // Diagnostics
     GetLogs() (string, error) // Returns recent logs
     ```

2. **Background Service**
   - Foreground Service implementation for yggstack
   - Persistent notification with:
     - Connection status indicator
     - Current Yggdrasil IPv6 address
     - Number of active peers
     - Stop button
   - Service lifecycle management (start/stop/restart)

3. **Yggdrasil Configuration**
   - Auto-generation of private key on first launch
   - Manual private key override capability
   - Peer connection string validation (tcp://, tls://, socks://)
   - Real-time Yggdrasil IP address display after connection

4. **Peer Management**
   - Add peers via UI (one at a time)
   - Edit existing peers (tap to edit)
   - Delete peers (delete button per entry)
   - Peer connection status monitoring

5. **Logging System**
   - LogCallback implementation for yggstack
   - Log buffer with timestamp (max 10MB)
   - Log viewer screen in Diagnostics section
   - Auto-scroll to latest log entries
   - Export logs to file (.txt) for sharing

6. **Diagnostics Screens**
   - **Config Viewer:** Display generated yggdrasil.conf (read-only, JSON formatted)
   - **Peer Status:** Show connection status for each peer:
     - Peer URI
     - Connection state (connected/disconnected)
     - Latency (if available)
     - Bytes sent/received
   - **Logs:** Real-time log display with filtering options

7. **Connectivity Testing**
   - Ping test functionality for Yggdrasil IPv6 addresses
   - Test button in peer status screen
   - Display ping results (latency, packet loss)
   - Visual indicator of connectivity status

#### Acceptance Criteria:
- [ ] Yggstack service runs successfully in background
- [ ] Persistent notification displays correct information
- [ ] Peers can be added, edited, and deleted
- [ ] Private key can be replaced by user
- [ ] Yggdrasil IP address displays after successful connection
- [ ] Logs are displayed in real-time with timestamps
- [ ] Logs can be exported to file
- [ ] Configuration viewer shows current yggdrasil.conf
- [ ] Peer status screen shows active connections
- [ ] Ping test works for Yggdrasil addresses

---

### Phase 3: Advanced Port Forwarding
**Objective:** Implement proxy configuration and port forwarding features

#### Deliverables:

1. **Proxy Configuration**
   - SOCKS proxy setup (-socks parameter)
   - DNS nameserver configuration (-nameserver parameter)
   - Enable/disable toggle for entire section
   - Input validation for IP:port format
   - Settings persist when section is disabled

2. **Expose Local Port to Yggdrasil**
   - Editable list of port mappings with fields:
     - Protocol (TCP/UDP dropdown)
     - Local port (number input)
     - Local IP address (default: 127.0.0.1)
     - Yggdrasil port (number input)
   - Add/Edit/Delete functionality
   - Visual representation: `tcp,80,127.0.0.1,8080`
   - Maps to yggstack parameters:
     - `-remote-tcp 80:127.0.0.1:8080`
     - `-remote-udp 53:127.0.0.1:53`
   - Enable/disable toggle for entire section

3. **Forward Remote Port to Local**
   - Editable list of port mappings with fields:
     - Protocol (TCP/UDP dropdown)
     - Local IP address (127.0.0.1 or [::1])
     - Local port (number input)
     - Remote Yggdrasil IPv6 address
     - Remote port (number input)
   - Add/Edit/Delete functionality
   - Visual representation: `127.0.0.1:8080:[2a03:94e0:ffff:185:181:60:0:111]:8080`
   - Maps to yggstack parameters:
     - `-local-tcp 127.0.0.1:8080:<remote-yggdrasil-ipv6>:8080`
     - `-local-udp 127.0.0.1:5353:<remote-yggdrasil-ipv6>:53`
   - Enable/disable toggle for entire section

4. **Input Validation**
   - Port range: 1-65535
   - IPv4 format validation (xxx.xxx.xxx.xxx)
   - IPv6 format validation (including Yggdrasil addresses)
   - Protocol selection (TCP/UDP)
   - Real-time error highlighting
   - Prevent saving invalid configurations

5. **Port Mapping Status**
   - Display active port mappings in diagnostics
   - Show which mappings are currently active
   - Connection counts per mapping (if available)

#### Acceptance Criteria:
- [ ] SOCKS proxy configuration works correctly
- [ ] DNS nameserver configuration functions properly
- [ ] Local ports can be exposed to Yggdrasil network
- [ ] Remote Yggdrasil ports can be forwarded to local
- [ ] All sections can be enabled/disabled independently
- [ ] Settings persist between app restarts
- [ ] Input validation prevents invalid configurations
- [ ] Port mappings display in diagnostics section
- [ ] All configurations disable when service stops

---

## 6. User Interface Specifications

### 6.1 Navigation Structure
```
Bottom Navigation:
├── Configuration (Home) [🏠]
├── Diagnostics [📊]
└── Settings [⚙️]
```

### 6.2 Configuration Screen Layout
```
┌─────────────────────────────────────┐
│ ┌─ Peers ────────────────────────┐ │
│ │ + Add Peer                      │ │
│ │ • tcp://peer1:1514        [×]   │ │
│ │ • tls://peer2:1515        [×]   │ │
│ │ [Enter peer URI...]             │ │
│ └─────────────────────────────────┘ │
│                                     │
│ ┌─ Private Key ──────────────────┐ │
│ │ [••••••••••••••••••••]      [👁] │ │
│ └─────────────────────────────────┘ │
│                                     │
│ ┌─ Your Yggdrasil IP ────────────┐ │
│ │ 324:71e:281a:9ed3::1       [📋] │ │
│ └─────────────────────────────────┘ │
│                                     │
│ ┌─ Proxy Configuration ───── [🔘]┐ │
│ │ SOCKS Proxy:                    │ │
│ │ [127.0.0.1:1080]                │ │
│ │ DNS Server:                     │ │
│ │ [[324:71e:281a:9ed3::53]:53]   │ │
│ └─────────────────────────────────┘ │
│                                     │
│ ┌─ Expose Local Port ────── [🔘]┐ │
│ │ + Add Mapping                   │ │
│ │ tcp 80 127.0.0.1 → 8080   [×]  │ │
│ └─────────────────────────────────┘ │
│                                     │
│ ┌─ Forward Remote Port ───── [🔘]┐ │
│ │ + Add Mapping                   │ │
│ │ tcp [2a03::111]:8080 → :8080[×]│ │
│ └─────────────────────────────────┘ │
│                                     │
│         [▶ Start Service]           │
└─────────────────────────────────────┘
```

### 6.3 Diagnostics Screen
```
Top Tabs:
├── Config Viewer (yggdrasil.conf)
├── Peer Status
└── Logs
```

### 6.4 Settings Screen
- Theme selection (Light/Dark/System)
- Log file management (clear, export)
- About section (version, library versions)

---

## 7. Data Models

### 7.1 Configuration Model
```kotlin
data class YggstackConfig(
    val peers: List<String> = emptyList(),
    val privateKey: String = "",
    val socksProxy: String = "",
    val dnsServer: String = "",
    val proxyEnabled: Boolean = true,
    val exposeMappings: List<ExposeMapping> = emptyList(),
    val exposeEnabled: Boolean = true,
    val forwardMappings: List<ForwardMapping> = emptyList(),
    val forwardEnabled: Boolean = true
)

data class ExposeMapping(
    val protocol: Protocol,
    val localPort: Int,
    val localIp: String,
    val yggPort: Int
)

data class ForwardMapping(
    val protocol: Protocol,
    val localIp: String,
    val localPort: Int,
    val remoteIp: String,
    val remotePort: Int
)

enum class Protocol { TCP, UDP }
```

### 7.2 Service State
```kotlin
sealed class ServiceState {
    object Stopped : ServiceState()
    object Starting : ServiceState()
    object Running : ServiceState()
    object Stopping : ServiceState()
    data class Error(val message: String) : ServiceState()
}
```

---

## 8. GitHub Actions Workflow

### 8.1 Build Trigger
- Trigger on tag push matching pattern: `v*.*.*` or `*.*.*`
- Example: `v1.0.0`, `1.2.3`

### 8.2 Build Steps
```yaml
name: Build and Release APK

on:
  push:
    tags:
      - '[0-9]+.[0-9]+.[0-9]+'

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - Checkout code
      - Setup JDK 17
      - Cache Gradle dependencies
      - Build debug APK
      - Sign APK with self-signed certificate
      - Create GitHub Release
      - Upload APK to release
```

### 8.3 Signing Configuration
- Self-signed keystore stored in GitHub Secrets
- Secrets required:
  - `KEYSTORE_FILE` (base64 encoded)
  - `KEYSTORE_PASSWORD`
  - `KEY_ALIAS`
  - `KEY_PASSWORD`

---

## 9. Testing Requirements

### 9.1 Unit Tests
- ViewModel logic testing
- Configuration validation
- Data model serialization/deserialization

### 9.2 Integration Tests
- Service lifecycle management
- yggstack.aar binding interaction
- Configuration persistence

### 9.3 UI Tests
- Navigation flow
- Form input validation
- State management

### 9.4 Manual Testing Scenarios
- Fresh install → first launch → auto-config generation
- Add/edit/delete peers
- Override private key
- Enable/disable sections
- Start/stop service multiple times
- Background service persistence
- Log viewer functionality
- Configuration export viewing
- Ping test execution
- Port forwarding validation

---

## 10. Security Considerations

### 10.1 Data Storage
- Private key stored in plain text in SharedPreferences
- No additional encryption required
- No biometric authentication
- Standard Android app sandboxing

### 10.2 Network Security
- All Yggdrasil traffic encrypted by protocol
- No additional app-level encryption needed
- Standard network permissions

### 10.3 Permissions Required
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```

---

## 11. Performance Requirements

- App launch time: < 2 seconds
- Service start time: < 5 seconds
- UI responsiveness: < 100ms for user interactions
- Log file size: Max 10MB (auto-rotation)
- Memory usage: < 100MB during normal operation

---

## 12. Localization

- **Phase 1-3:** English only
- UI strings externalized to strings.xml for future localization
- Date/time formatting: ISO 8601 for logs

---

## 13. Error Handling

### 13.1 Error Categories
- **Network Errors:** Peer connection failures
- **Configuration Errors:** Invalid input, malformed config
- **Service Errors:** Failed to start/stop service
- **Library Errors:** yggstack.aar binding failures

### 13.2 User Feedback
- Toast messages for quick feedback
- Snackbar for actionable errors
- Alert dialogs for critical errors
- Error logging to diagnostics

---

## 14. Dependencies & External Libraries

### 14.1 Required Libraries
```gradle
// Core Android
androidx.core:core-ktx
androidx.lifecycle:lifecycle-runtime-ktx
androidx.lifecycle:lifecycle-viewmodel-compose

// Compose
androidx.activity:activity-compose
androidx.compose.ui:ui
androidx.compose.material3:material3
androidx.navigation:navigation-compose

// Data & Storage
androidx.datastore:datastore-preferences

// Coroutines
org.jetbrains.kotlinx:kotlinx-coroutines-android

// Local library
libs/yggstack.aar
```

---

## 15. Development Milestones

### Milestone 1: Foundation (Week 1-2)
- Project setup
- UI scaffolding
- Basic navigation
- Data models
- GitHub Actions workflow

### Milestone 2: Core Features (Week 3-4)
- Service implementation
- Yggdrasil integration
- Mobile bindings enhancement
- Peer management
- Logging system

### Milestone 3: Advanced Features (Week 5-6)
- Proxy configuration
- Port forwarding
- Validation
- Diagnostics screens
- Polish and testing

---

## 16. Known Limitations

1. **No VPN API:** Application does not use Android VPN Service API
2. **No System-wide Traffic Routing:** Only applications configured to use SOCKS proxy will route through Yggdrasil
3. **Manual Peer Management:** No automatic peer discovery beyond multicast
4. **Single Instance:** Only one yggstack instance can run at a time

---

## 18. Appendix

### A. Yggstack Parameters Mapping

| UI Field | Yggstack Parameter | Example |
|----------|-------------------|---------|
| SOCKS Proxy | `-socks` | `127.0.0.1:1080` |
| DNS Server | `-nameserver` | `[324:71e::53]:53` |
| Expose TCP | `-remote-tcp` | `80:127.0.0.1:8080` |
| Expose UDP | `-remote-udp` | `53:127.0.0.1:53` |
| Forward TCP | `-local-tcp` | `127.0.0.1:8080:[2a03::111]:8080` |
| Forward UDP | `-local-udp` | `127.0.0.1:5353:[2a03::111]:53` |

### B. Yggdrasil Config Structure
See `prompts/yggstack_genconf_output.txt` for full example.

### C. Mobile Bindings Reference
See `lib/yggstack/mobile/yggstack.go` for current implementation.

---

## Document Revision History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2025-12-10 | Development Team | Initial PRD creation |

