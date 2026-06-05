# BikeComputer – Android + ESP32 Navigation System

A hybrid GPS bike navigation system: **Android handles routing & maps, ESP32 displays navigation on TFT.**

## Quick Start

1. **Open** `app_remis` in Android Studio
2. **Sync** Gradle (wait for dependencies)
3. **Connect** Android device (API 31+)
4. **Run** app (Shift+F10)
5. **Grant** Bluetooth + Location permissions
6. **Auto-scans** for `BikeComputer` device
7. **Tap map** to set destination
8. **Press START** to send route to ESP32

## Architecture

```
Phone (Navigation)          ←BLE→         ESP32 (Display)
├ Maps + Routing                          ├ TFT Screen
├ GPS Location                            ├ Turn Arrows
├ Route Simplification                    ├ Ride Stats
└ Destination Search                      └ Waypoint Nav
```

## Key Files

| File                     | Purpose                              |
| ------------------------ | ------------------------------------ |
| `MainActivity.kt`        | Compose entry, permissions, BLE scan |
| `NavigationScreen.kt`    | Map UI, buttons, route display       |
| `NavigationViewModel.kt` | State, routing, BLE integration      |
| `BleManager.kt`          | GATT connection, send commands       |
| `RouteSimplifier.kt`     | Douglas-Peucker simplification       |
| `RouteService.kt`        | OSRM API (bicycle routing)           |

## Features

✅ **BLE Connection** – Auto-scan, auto-reconnect  
✅ **Live Map** – OSMDroid, tap to set destination  
✅ **Route Generation** – OSRM (free, no API key)  
✅ **Route Simplification** – 20–40 waypoints for ESP32  
✅ **Ride Controls** – START/STOP/RESET  
✅ **Simulated Mode** – Test without GPS  
✅ **Dark UI** – Cycling computer aesthetic  
✅ **Telemetry Ready** – Architecture for future SPD/DIST/NAV

## BLE Protocol

### UUIDs

```
SERVICE:  12345678-1234-1234-1234-1234567890ab
RX (Write): 12345678-1234-1234-1234-1234567890ad
TX (Notify): 12345678-1234-1234-1234-1234567890ac
```

### Commands

```
START                                      – Start ride
STOP                                       – Stop ride
RESET                                      – Reset stats
ROUTE:48.1,16.3;48.2,16.4;...             – Send waypoints
```

## Permissions

```
BLUETOOTH + BLUETOOTH_SCAN + BLUETOOTH_CONNECT
ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION
INTERNET (for OSRM routing)
```

## Dependencies

- **Compose** (UI framework)
- **OSMDroid** (maps)
- **Retrofit** (OSRM API)
- **Play Services Location** (GPS)
- **Coroutines** (async)

## Troubleshooting

| Issue                    | Solution                                                      |
| ------------------------ | ------------------------------------------------------------- |
| Bluetooth not connecting | Check `BikeComputer` is on, verify UUIDs match ESP32          |
| Route not fetching       | Verify internet, check OSRM: https://router.project-osrm.org/ |
| Route too long           | Simplifier targets 35 waypoints; try shorter route            |
| Location not updating    | Grant runtime permission, enable GPS, check sky view          |

---

## Full Documentation

See **[NAVIGATION_GUIDE.md](NAVIGATION_GUIDE.md)** for:

- Complete architecture & design decisions
- Detailed feature explanations
- Usage flows & testing
- Future roadmap

---

## Next Steps

1. ✅ Android app (you are here)
2. Build ESP32 firmware to receive ROUTE packets
3. Add telemetry display (speed, distance, battery)
4. Offline map support
5. Voice guidance

---

**Status:** Navigation engine complete. Ready for ESP32 integration.
