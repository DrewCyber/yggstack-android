# Yggstack Android

Native Android application for Yggdrasil network connectivity using yggstack.

## Features

- **Peer Management**: Add, edit, and remove Yggdrasil peers
- **SOCKS Proxy Configuration**: Configure SOCKS proxy and DNS resolver
- **Port Forwarding**: Expose local ports to Yggdrasil network and forward remote ports
- **Background Service**: Run Yggstack without VPN API
- **Material Design 3**: Modern UI with light/dark theme support

## Requirements

- Android 6.0+ (API 23+)
- yggstack.aar library (included in `app/libs/`)

## Building

### Using Gradle

```bash
./gradlew assembleDebug
```

### Using Android Studio

1. Open project in Android Studio
2. Wait for Gradle sync
3. Build > Make Project
4. Run > Run 'app'

## Project Structure

```
app/
├── src/main/
│   ├── java/io/github/yggstack/android/
│   │   ├── data/              # Data models and repositories
│   │   ├── ui/
│   │   │   ├── configuration/ # Configuration screen
│   │   │   ├── diagnostics/   # Diagnostics screen
│   │   │   ├── settings/      # Settings screen
│   │   │   └── theme/         # Theme and styling
│   │   ├── MainActivity.kt
│   │   └── YggstackApplication.kt
│   ├── res/                   # Resources (layouts, strings, etc.)
│   └── AndroidManifest.xml
└── libs/
    └── yggstack.aar          # Yggstack mobile bindings
```

## Development Phases

### Phase 1: Basic UI and Build Setup ✅
- [x] Project structure
- [x] UI implementation with Compose
- [x] Data persistence with DataStore
- [x] Navigation between screens
- [x] GitHub Actions CI/CD

### Phase 2: Yggdrasil Core Functionality (In Progress)
- [x] Background service implementation
- [x] Yggstack integration
- [x] Peer management
- [x] Logging system
- [x] Diagnostics screens

### Phase 3: Advanced Port Forwarding
- [x] Proxy configuration
- [x] Port mapping management
- [ ] Input validation
- [x] Status monitoring

## GitHub Actions

The project includes automated builds via GitHub Actions. Push a version tag to trigger a build:

```bash
git tag 1.0.0
git push origin 1.0.0
```

Required secrets in GitHub repository settings:
- `KEYSTORE_FILE` - Base64 encoded keystore
- `KEYSTORE_PASSWORD` - Keystore password
- `KEY_ALIAS` - Key alias
- `KEY_PASSWORD` - Key password

## License

See LICENSE file in the yggstack library directory.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

## Support

For issues and questions, please use the GitHub issue tracker.

