Quick test steps to verify route transmission and ESP reception

1. Build & flash `program_remis_ESP.ino` to the ESP32 and open Serial Monitor at 115200.

2. Start the Android app and connect to the ESP (watch `BleManager` logs in Logcat).

3. From the app, tap a destination and press `SEND ROUTE TO BIKE`.
   - In Logcat filter for `BleManager` you should see a line like:
     Queued send (XXX bytes, writeType=Y, mtu=XXX): ROUTE:49.123456,18.123456;...
   - The BLE manager also exposes `lastSentPreview` state for the UI.

4. On the ESP Serial Monitor you should see:
   - `BLE CMD: READY` (initial)
   - Shortly after sending: `BLE CMD: ROUTE:...` (if using write callback) or `✅ ROUTE RECEIVED: N waypoints` when polling reads the new value.

5. If no `ROUTE RECEIVED` appears:
   - Confirm the app log shows the `Queued send` line.
   - If the app shows `Queued send` but ESP doesn't print route, increase chunk delay in `BleManager.kt` (variable `chunkDelay`) to 100ms and retry.
   - Alternatively, send the same `ROUTE:` string manually with nRF Connect to confirm ESP parses it.

Notes:

- The ESP firmware expects `ROUTE:lat,lon;lat,lon;...` with `.` decimal separator.
- We limit routes to the first 15 points on the app side to reduce fragmentation.
- If you prefer guaranteed delivery, we can implement per-chunk acknowledgements (more work on both sides).
