# Bike Controller (Android BLE Minimal)

Minimal Android Kotlin BLE app skeleton that can scan for a device named `BikeComputer`, connect, and send simple string commands (`START`, `STOP`, `RESET`, later `ROUTE`).

Files added:

- app/src/main/java/com/example/bikecontroller/BleManager.kt
- app/src/main/java/com/example/bikecontroller/MainActivity.kt
- app/src/main/AndroidManifest.xml
- app/src/main/res/layout/activity_main.xml

How to open:

1. Open the `app_remis` folder in Android Studio.
2. Let Gradle sync (you may need Android Studio to supply the Gradle wrapper).
3. Run on an Android 12+ device (BLE permissions required).

BLE UUIDs (must match ESP32):

SERVICE: 12345678-1234-1234-1234-1234567890ab
RX: 12345678-1234-1234-1234-1234567890ad (write)
TX: 12345678-1234-1234-1234-1234567890ac (notify)

Next step: build navigation engine
