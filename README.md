# SyncShare

SyncShare is an Android application for seamless peer-to-peer (P2P) and Bluetooth file synchronization and sharing. It allows users to discover nearby devices, connect using Wi-Fi Direct or Bluetooth, and synchronize folders or files efficiently, with conflict detection and resolution.

## Features

- **Wi-Fi Direct (P2P) and Bluetooth Support:**
  - Discover and connect to nearby devices using Wi-Fi Direct or Bluetooth.
  - Automatic switching between communication technologies.
- **File & Folder Synchronization:**
  - Sync entire folders or selected files between devices.
  - Handles file metadata, chunked transfer, and transfer resumption.
- **Conflict Detection & Resolution:**
  - Detects file conflicts (e.g., different versions) and allows user to resolve them.
- **Sync History:**
  - Maintains a history of sync operations, including errors and completion status.
- **Modern Android Architecture:**
  - Built with ViewModel, LiveData/StateFlow, and Jetpack Compose for UI.
- **Permissions Handling:**
  - Graceful handling of required permissions for Bluetooth, Wi-Fi, and storage.

## Getting Started

### Prerequisites

- Android Studio (Giraffe or newer recommended)
- Android device or emulator (API 26+ recommended for full feature support)
- Java 8+

### Setup

1. **Clone the repository:**
   ```sh
   git clone <your-repo-url>
   cd SyncShare
   ```
2. **Open in Android Studio:**
   - Open the project folder in Android Studio.
3. **Sync Gradle:**
   - Let Android Studio sync and download dependencies.
4. **Configure Permissions:**
   - The app requires permissions for Bluetooth, Wi-Fi, and storage. Grant these when prompted.

### Running the App

- Build and run the app on your device or emulator.
- Use the UI to scan for devices, connect, and sync files/folders.

## Project Structure

- `app/src/main/java/com/example/syncshare/`
  - `viewmodels/DevicesViewModel.kt` — Core logic for device discovery, connection, and sync.
  - `features/` — Wi-Fi Direct and Bluetooth broadcast receivers and helpers.
  - `protocol/` — Data classes and message types for sync protocol.
  - `ui/` — Jetpack Compose UI components and models.
  - `utils/` — Utility functions for permissions, file handling, etc.
- `app/src/main/res/` — UI resources (layouts, strings, icons).
- `gradle/libs.versions.toml` — Version catalog for dependencies.

## Permissions

SyncShare requires the following permissions:

- `ACCESS_FINE_LOCATION` and `NEARBY_WIFI_DEVICES` (for Wi-Fi Direct)
- `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT` (for Bluetooth)
- `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (for file access)

> **Note:** Some permissions are only required on Android 12+ or 13+.

## Troubleshooting

- **P2P or Bluetooth not working?**
  - Ensure all required permissions are granted.
  - Make sure Wi-Fi and Bluetooth are enabled on your device.
  - Some emulators may not support Wi-Fi Direct or Bluetooth features.
- **Gradle sync issues?**
  - Check your `libs.versions.toml` for missing or incorrect version references.

## Contributing

Contributions are welcome! To contribute:

1. Fork the repository
2. Create a new branch (`git checkout -b feature/your-feature`)
3. Make your changes
4. Commit and push (`git commit -am 'Add new feature'`)
5. Open a pull request

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

## Acknowledgements

- Android Jetpack
- Kotlin Coroutines
- Jetpack Compose
- Android Wi-Fi Direct and Bluetooth APIs
