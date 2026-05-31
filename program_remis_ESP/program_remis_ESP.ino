#include <TFT_eSPI.h>
#include <NimBLEDevice.h>
#include <TinyGPSPlus.h>

// ========================================
// GLOBAL STATE - REAL GPS ONLY
// ========================================

// GPS
TinyGPSPlus gps;
HardwareSerial GPSSerial(2); // UART2: RX=16, TX=17

float gpsLat = 0;
float gpsLon = 0;
float gpsSpeed = 0;  // km/h
float gpsCourse = 0; // heading degrees
bool gpsValid = false;
float speedFiltered = 0;

// Tracking
bool tracking = false;
unsigned long startTime = 0;
float distanceMeters = 0;

// Distance calculation
float lastLat = 0;
float lastLon = 0;
bool hasLast = false;
float totalDistanceMeters = 0;

// Route & Navigation
#define MAX_POINTS 20
float routeLat[MAX_POINTS];
float routeLon[MAX_POINTS];
int routeSize = 0;
int routeIndex = 0;

String currentNav = "STRAIGHT";
int currentNavDistance = 0;

// BLE
NimBLECharacteristic *pRx = nullptr;
std::string lastBleValue = "";

#define SERVICE_UUID "12345678-1234-1234-1234-1234567890ab"
#define CHAR_RX_UUID "12345678-1234-1234-1234-1234567890ad"

// Display
TFT_eSPI tft = TFT_eSPI();

// ========================================
// MATH FUNCTIONS
// ========================================

float haversine(float lat1, float lon1, float lat2, float lon2)
{
  const float R = 6371000; // Earth radius in meters
  float dLat = radians(lat2 - lat1);
  float dLon = radians(lon2 - lon1);

  float a =
      sin(dLat / 2) * sin(dLat / 2) +
      cos(radians(lat1)) * cos(radians(lat2)) *
          sin(dLon / 2) * sin(dLon / 2);

  float c = 2 * atan2(sqrt(a), sqrt(1 - a));
  return R * c;
}

float calculateBearing(float lat1, float lon1, float lat2, float lon2)
{
  float dLon = radians(lon2 - lon1);

  lat1 = radians(lat1);
  lat2 = radians(lat2);

  float y = sin(dLon) * cos(lat2);

  float x =
      cos(lat1) * sin(lat2) -
      sin(lat1) * cos(lat2) * cos(dLon);

  float brng = degrees(atan2(y, x));
  brng = fmod((brng + 360.0), 360.0);

  return brng;
}

String getTurnDirection(float currentHeading, float targetBearing)
{
  float diff = targetBearing - currentHeading;

  while (diff < -180)
    diff += 360;
  while (diff > 180)
    diff -= 360;

  if (diff > -20 && diff < 20)
    return "STRAIGHT";

  if (diff >= 20 && diff < 100)
    return "RIGHT";

  if (diff <= -20 && diff > -100)
    return "LEFT";

  return "U-TURN";
}

// ========================================
// GPS MODULE (REAL)
// ========================================

void initGPS()
{
  Serial.println("Initializing GPS on UART2 (RX=16, TX=17)...");
  GPSSerial.begin(9600, SERIAL_8N1, 16, 17);
  Serial.println("✅ GPS Serial initialized at 9600 baud");
}

void updateGPS()
{
  // Feed GPS data to TinyGPS
  while (GPSSerial.available())
  {
    gps.encode(GPSSerial.read());
  }

  // Check location validity
  if (gps.location.isValid())
  {
    gpsLat = gps.location.lat();
    gpsLon = gps.location.lng();
    gpsValid = true;
  }
  else
  {
    gpsValid = false;
  }

  // Check speed validity
  if (gps.speed.isValid())
  {
    gpsSpeed = gps.speed.kmph();
    // Low-pass filter for smoother readings
    speedFiltered = (speedFiltered * 0.8) + (gpsSpeed * 0.2);
  }

  // Check course (heading) validity
  if (gps.course.isValid())
  {
    gpsCourse = gps.course.deg();
  }

  // Debug: Print GPS status every 5 seconds
  static unsigned long lastPrint = 0;
  if (millis() - lastPrint > 5000)
  {
    lastPrint = millis();

    Serial.print("GPS Status - Valid: ");
    Serial.print(gpsValid ? "YES" : "NO");

    if (gpsValid)
    {
      Serial.print(" | Lat: ");
      Serial.print(gpsLat, 6);
      Serial.print(" | Lon: ");
      Serial.print(gpsLon, 6);
      Serial.print(" | Speed: ");
      Serial.print(gpsSpeed, 1);
      Serial.print(" km/h | Course: ");
      Serial.print(gpsCourse, 1);
      Serial.println(" deg");
    }
    else
    {
      Serial.print(" | Satellites: ");
      Serial.print(gps.satellites.value());
      Serial.println();
    }
  }
}

void updateDistance()
{
  if (!gpsValid)
    return;

  if (hasLast)
  {
    float d = haversine(lastLat, lastLon, gpsLat, gpsLon);

    // Filter out GPS noise (jitter) - only accept distances < 20m
    if (d < 20 && d > 0)
    {
      totalDistanceMeters += d;
      distanceMeters += d;
    }
  }

  lastLat = gpsLat;
  lastLon = gpsLon;
  hasLast = true;
}

// ========================================
// NAVIGATION ENGINE
// ========================================

void updateNavigation()
{
  if (!gpsValid)
  {
    currentNav = "NO GPS";
    currentNavDistance = 0;
    return;
  }

  if (routeSize == 0)
  {
    currentNav = "NO ROUTE";
    currentNavDistance = 0;
    return;
  }

  if (routeIndex >= routeSize)
  {
    currentNav = "ARRIVED";
    currentNavDistance = 0;
    return;
  }

  float targetLat = routeLat[routeIndex];
  float targetLon = routeLon[routeIndex];

  // Distance to current target
  float dist = haversine(gpsLat, gpsLon, targetLat, targetLon);
  currentNavDistance = (int)dist;

  // Check if reached waypoint (15 meter threshold)
  if (dist < 15)
  {
    routeIndex++;

    if (routeIndex >= routeSize)
    {
      currentNav = "ARRIVED";
      currentNavDistance = 0;
      return;
    }

    targetLat = routeLat[routeIndex];
    targetLon = routeLon[routeIndex];
  }

  // Calculate bearing to target
  float bearing = calculateBearing(gpsLat, gpsLon, targetLat, targetLon);

  // Get turn direction based on current heading
  currentNav = getTurnDirection(gpsCourse, bearing);
}

// ========================================
// DISPLAY
// ========================================

void drawUI(float speed, float distance, unsigned long elapsed)
{
  tft.fillScreen(TFT_BLACK);

  // SPEED
  tft.setTextColor(TFT_WHITE);
  tft.setTextSize(2);
  tft.drawString("SPEED: " + String(speed, 1) + " km/h", 10, 20);

  // DISTANCE
  tft.drawString("DIST: " + String(distance / 1000.0, 2) + " km", 10, 60);

  // TIME
  int m = elapsed / 60;
  int s = elapsed % 60;
  tft.drawString("TIME: " + String(m) + ":" + (s < 10 ? "0" : "") + String(s), 10, 100);

  // STATUS
  if (tracking)
  {
    tft.setTextColor(TFT_GREEN);
    tft.drawString("TRACKING", 10, 140);
  }
  else
  {
    tft.setTextColor(TFT_RED);
    tft.drawString("STOPPED", 10, 140);
  }

  // GPS Status
  tft.setTextColor(gpsValid ? TFT_GREEN : TFT_RED);
  tft.setTextSize(1);
  tft.drawString(gpsValid ? "GPS OK" : "NO GPS", 10, 160);

  // NAVIGATION - Arrow symbols
  tft.setTextColor(TFT_YELLOW);
  tft.setTextSize(3);

  String arrow = "";
  if (currentNav == "STRAIGHT")
  {
    arrow = "^";
  }
  else if (currentNav == "LEFT")
  {
    arrow = "<";
  }
  else if (currentNav == "RIGHT")
  {
    arrow = ">";
  }
  else if (currentNav == "U-TURN")
  {
    arrow = "V";
  }
  else if (currentNav == "ARRIVED")
  {
    arrow = "!";
  }
  else if (currentNav == "NO GPS")
  {
    arrow = "?";
  }
  else if (currentNav == "NO ROUTE")
  {
    arrow = "-";
  }

  tft.drawString(arrow, 200, 140);

  // Navigation distance
  tft.setTextColor(TFT_YELLOW);
  tft.setTextSize(1);
  tft.drawString("NAV: " + currentNav + " in " + String(currentNavDistance) + "m", 10, 200);

  // Waypoint counter
  if (routeSize > 0)
  {
    tft.drawString("Wp: " + String(routeIndex) + "/" + String(routeSize), 10, 225);
  }

  // GPS coordinates
  tft.setTextColor(TFT_WHITE);
  tft.setTextSize(1);
  if (gpsValid)
  {
    tft.drawString("Lat: " + String(gpsLat, 5), 10, 240);
    tft.drawString("Lon: " + String(gpsLon, 5), 10, 250);
  }
}

// ========================================
// BLE COMMAND PROCESSING
// ========================================

void processBleCommand(std::string cmd)
{
  String value = String(cmd.c_str());
  value.trim();

  Serial.print("BLE CMD: ");
  Serial.println(value);

  if (value.equalsIgnoreCase("START"))
  {
    tracking = true;
    startTime = millis();
    distanceMeters = 0;
    Serial.println("✅ TRACKING STARTED");
  }
  else if (value.equalsIgnoreCase("STOP"))
  {
    tracking = false;
    Serial.println("✅ TRACKING STOPPED");
  }
  else if (value.equalsIgnoreCase("RESET"))
  {
    startTime = millis();
    distanceMeters = 0;
    totalDistanceMeters = 0;
    routeIndex = 0;
    hasLast = false;
    Serial.println("✅ TRIP RESET");
  }
  else if (value.startsWith("ROUTE:"))
  {
    routeSize = 0;
    value.remove(0, 6);

    int start = 0;
    while (true)
    {
      int sep = value.indexOf(';', start);
      String part = (sep == -1) ? value.substring(start) : value.substring(start, sep);

      int comma = part.indexOf(',');
      if (comma > 0 && routeSize < MAX_POINTS)
      {
        routeLat[routeSize] = part.substring(0, comma).toFloat();
        routeLon[routeSize] = part.substring(comma + 1).toFloat();
        routeSize++;
      }

      if (sep == -1)
        break;
      start = sep + 1;
    }

    routeIndex = 0;
    Serial.print("✅ ROUTE RECEIVED: ");
    Serial.print(routeSize);
    Serial.println(" waypoints");
  }
}

// ========================================
// SETUP
// ========================================

void setup()
{
  Serial.begin(115200);
  delay(2000);

  Serial.println("\n\n=== BIKE COMPUTER SETUP (REAL GPS) ===\n");

  // DISPLAY
  tft.init();
  tft.setRotation(1);
  tft.fillScreen(TFT_BLACK);
  tft.drawString("BOOTING...", 10, 10);

  // GPS
  initGPS();

  // BLE - POLLING MODE
  Serial.println("Initializing BLE...");
  NimBLEDevice::init("BikeComputer");
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);

  NimBLEServer *pServer = NimBLEDevice::createServer();
  NimBLEService *service = pServer->createService(SERVICE_UUID);

  pRx = service->createCharacteristic(
      CHAR_RX_UUID,
      NIMBLE_PROPERTY::READ |
          NIMBLE_PROPERTY::WRITE |
          NIMBLE_PROPERTY::WRITE_NR |
          NIMBLE_PROPERTY::NOTIFY);

  pRx->setValue("READY");
  service->start();

  NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->start();

  Serial.println("✅ BLE READY");

  tft.fillScreen(TFT_BLACK);
  tft.drawString("READY", 10, 10);

  Serial.println("✅ SETUP COMPLETE\n");
}

// ========================================
// MAIN LOOP
// ========================================

void loop()
{
  // Update GPS
  updateGPS();

  // POLL BLE for changes
  if (pRx != nullptr)
  {
    std::string currentValue = pRx->getValue();

    if (currentValue != lastBleValue)
    {
      lastBleValue = currentValue;
      if (currentValue.length() > 0)
      {
        processBleCommand(currentValue);
      }
    }
  }

  // Update distance if tracking and GPS is valid
  if (tracking && gpsValid)
  {
    updateDistance();
  }

  // Update navigation
  updateNavigation();

  // Update display
  unsigned long elapsed = tracking ? (millis() - startTime) / 1000 : 0;
  drawUI(speedFiltered, distanceMeters, elapsed);

  // TELEMETRY PUSH TO PHONE
  static unsigned long lastBleUpdate = 0;
  if (millis() - lastBleUpdate > 1000) {
    lastBleUpdate = millis();
    if (pRx != nullptr) {
      // Send GPS status
      pRx->setValue(gpsValid ? "GPS:1" : "GPS:0");
      pRx->notify();

      // Send Speed
      String spdMsg = "SPD:" + String(speedFiltered, 1);
      pRx->setValue(spdMsg.c_str());
      pRx->notify();

      // Send Distance
      String distMsg = "DIST:" + String((int)distanceMeters);
      pRx->setValue(distMsg.c_str());
      pRx->notify();
    }
  }

  delay(100);
}
