# BikeComputer – Android Navigation + ESP32 Ride Display

A hybrid Android + ESP32 GPS bike navigation system where the phone handles routing and maps, while the ESP32 displays navigation on a TFT screen.

## Architecture

```
Android Phone (Navigation Brain)          ESP32 (Display + Telemetry)
├── Maps & routing                        ├── TFT Dashboard
├── GPS location                          ├── Turn indicators
├── Route calculation                     ├── Ride statistics
├── Internet APIs (OSRM)                  ├── Waypoint tracking
└── BLE communication          ←————→      └── Lightweight nav engine
```

**Design principle:** Phone does heavy lifting (routing, maps, networking). ESP32 stays lightweight (display, embedded logic only).

---

## Android App Features

### 1. **BLE Connection Layer**

- Scans for `BikeComputer` device
- Maintains stable GATT connection
- Auto-reconnects if disconnected
- Shows connection state: `Disconnected → Connecting → Connected → RouteSent → NavigationActive`

### 2. **Navigation Screen**

- **Live map** with OSMDroid (OpenStreetMap)
- **Tap to set destination** directly on map
- Shows route with cyan polyline
- Displays current location (blue marker)
- Shows destination (red marker)
- Real-time location updates (5-second intervals)

### 3. **Route Generation**

- Uses **OSRM (Open Source Routing Machine)**
- Calculates bicycle-optimized routes
- Returns full coordinate list for map preview
- **No internet required on ESP32** – all routing happens on phone

### 4. **Route Simplification (Critical)**

- **Douglas-Peucker algorithm** reduces waypoints
- Target: 20–40 waypoints (ESP32 memory constraint)
- Preserves turns and shape accuracy
- Adaptive epsilon for aggressive reduction if needed

### 5. **BLE Route Transmission**

Sends route as simple string protocol:

```
ROUTE:48.210033,16.363449;48.210089,16.363558;48.210145,16.363667
```

- UTF-8 encoded
- Max ~512 chars (one BLE write)
- Sent to RX characteristic (`12345678-1234-1234-1234-1234567890ad`)

### 6. **Ride Controls**

Three simple commands:

```
START    – Begin ride, start ESP32 timer
STOP     – Pause ride
RESET    – Clear stats, reset trip
```

### 7. **Simulated Navigation Mode**

- **Indoor testing without GPS**
- Simulates movement along route (2-second per waypoint)
- Perfect for development and testing
- Toggle via simulation UI

### 8. **UI Design**

- **Dark theme** – high contrast for daylight cycling
- **Large touch targets** – easy to tap while riding
- **Cycling computer aesthetic** – minimalist, focused
- **Status bar** – BLE state always visible
- **Speed/distance cards** – from ESP32 telemetry (future)

---

## File Structure

```
app/src/main/
├── java/com/example/bikecontroller/
│   ├── MainActivity.kt              – Compose entry point, permissions
│   ├── NavigationScreen.kt          – Map UI, buttons, status display
│   ├── NavigationViewModel.kt       – State management, route logic
│   ├── NavigationViewModelFactory.kt – ViewModel factory
│   ├── BleManager.kt                – BLE connection, send/receive
│   ├── RouteManager.kt              – Route simplification (alt impl)
│   ├── RouteSimplifier.kt           – Douglas-Peucker algorithm
│   ├── RouteService.kt              – OSRM API Retrofit interface
│   └── LocationProvider.kt          – GPS location updates
├── AndroidManifest.xml              – Permissions, activity declaration
├── res/layout/activity_main.xml     – (unused, Compose-based)
└── res/values/...                   – Strings, colors (Compose uses Material3)
```

---

## BLE Protocol

### UUIDs (Match ESP32)

```
SERVICE:  12345678-1234-1234-1234-1234567890ab
RX (Write): 12345678-1234-1234-1234-1234567890ad
TX (Notify): 12345678-1234-1234-1234-1234567890ac
```

### Commands (Android → ESP32)

```
START          – Start ride timer
STOP           – Stop ride
RESET          – Reset stats
ROUTE:lat,lon;lat,lon;...  – Send simplified waypoint list
```

### Telemetry (ESP32 → Android, future)

```
SPD:24.5       – Current speed (km/h)
DIST:5230      – Distance traveled (m)
NAV:LEFT       – Next turn direction
BAT:82         – Battery percentage
```

Currently logged but not displayed in UI. Architecture ready for future notifications.

---

## Setup & Build

### Prerequisites

- **Android Studio** (latest Giraffe+)
- **Gradle 8.5+** (included in wrapper)
- **Java 21** (matches Gradle compatibility)
- **Android SDK 33+** (compileSdk 33, minSdk 21)

### Steps

1. **Open project** in Android Studio
2. **Sync Gradle** (File → Sync Now)
   - Will download dependencies (Compose, Retrofit, OSMDroid, Play Services)
   - First sync takes 2–3 minutes
3. **Connect device** or launch emulator (API 31+)
4. **Run app** (Shift+F10 or Run → Run 'app')
5. **Grant permissions** (Bluetooth, Location)
6. **Auto-scan** begins – looks for `BikeComputer` device

### Gradle Configuration

- `app/build.gradle` – App module setup
- `settings.gradle` – Plugin repositories
- `gradle/wrapper/gradle-wrapper.properties` – Gradle version (8.5)

### Key Dependencies

```gradle
// Compose
androidx.compose.ui:ui:1.4.3
androidx.compose.material3:material3:1.0.1

// Maps
org.osmdroid:osmdroid-android:6.1.18

// Routing
com.squareup.retrofit2:retrofit:2.9.0
com.squareup.retrofit2:converter-gson:2.9.0

// Location
com.google.android.gms:play-services-location:21.0.1

// Coroutines
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4
```

---

## Usage Flow

### Normal Ride

```
1. Open app
   → Scans for BikeComputer
   → Connects (shows Connected status)

2. Tap on map
   → Sets destination marker
   → Fetches route from OSRM
   → Route drawn on map

3. Press START
   → Route is simplified (20–40 waypoints)
   → "ROUTE:..." packet sent over BLE
   → ESP32 displays navigation UI

4. During ride
   → Phone shows current location
   → ESP32 shows next turn, distance
   → Move through waypoints

5. Press STOP
   → Stops ESP32 timer
   → Can press START again to continue
   → Or RESET to clear stats
```

### Testing (Simulated Mode)

```
1. Open app, connect to BikeComputer
2. Tap map to set destination
3. Route is fetched and simplified
4. Toggle simulation (future UI button)
   → Moves cursor along route every 2 seconds
   → No GPS needed
5. Press START to begin ride
6. Monitor telemetry from ESP32 (future)
```

---

## Permissions Required

```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
```

App requests runtime permissions for Bluetooth and location on startup.

---

## Known Constraints

- **Route length:** Max ~512 chars over BLE (typically 30–50 waypoints)
- **ESP32 memory:** Simplification keeps waypoints to 20–40 max
- **Location update rate:** 5 seconds (balances accuracy vs battery)
- **Simulated speed:** 2 seconds per waypoint (adjustable)
- **Map tiles:** OSMDroid caches offline; initial load requires internet

---

## Future Enhancements

### Short-term

- [ ] Telemetry display (speed, distance, elevation)
- [ ] Reroute detection and automatic recalculation
- [ ] GPX import/export
- [ ] Waypoint history

### Medium-term

- [ ] Turn-by-turn voice guidance
- [ ] Offline maps (MBTiles or similar)
- [ ] Integration with Strava/Garmin APIs
- [ ] Smart rerouting on missed turn

### Long-term

- [ ] Heart rate sensor integration
- [ ] Power meter support
- [ ] Live weather on map
- [ ] Cadence-based fitness tracking
- [ ] Cloud sync for routes

---

## Troubleshooting

### Bluetooth not connecting

- Ensure `BikeComputer` device is on and advertising
- Check BLE UUIDs match ESP32 exactly
- Verify Android 12+ has location permission (required for BLE scan)
- Try resetting Bluetooth on phone

### Route not fetching

- Check internet connection (OSRM API requires it)
- Verify OSRM is reachable: https://router.project-osrm.org/
- Check tap location is valid (not too far from start)

### Route too long

- Simplification targets 35 waypoints max
- If route still fails to send, try reducing epsilon in `RouteSimplifier`
- Consider shorter routes or waypoint deletions

### Location not updating

- Verify location permission granted at runtime
- Ensure device has GPS enabled
- Try toggling simulated mode for testing
- Check device is outside or has clear sky view

---

## Architecture Decisions

### Why OSMDroid instead of Google Maps?

- Free and open-source
- No API key required
- Lighter footprint
- Integrates well with Jetpack Compose

### Why OSRM for routing?

- Free public API
- No account needed
- Good bicycle routing support
- Fast response times

### Why Douglas-Peucker?

- Excellent for preserving turns
- Mathematically proven accuracy
- Adjustable epsilon for flexibility
- O(n²) worst-case but fast in practice

### Why Kotlin + Compose?

- Modern Android ecosystem
- Reactive state management
- Less boilerplate than XML + Java
- Excellent Coroutine support

---

## Contributing

- **Code style:** Follow Kotlin conventions (ktlint)
- **Logging:** Use Android Log, debug level
- **Testing:** Unit tests for RouteSimplifier and RouteManager
- **BLE:** Keep protocol backward-compatible with ESP32

---

## License

MIT (or specify your license)

---

## Support

For issues, questions, or feature requests, open an issue or contact the team.

**Next step:** Add telemetry display, turn-by-turn guidance, and offline map support.
