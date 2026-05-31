#include <TFT_eSPI.h>
#include <NimBLEDevice.h>
#include <TinyGPSPlus.h>

TinyGPSPlus gps;
HardwareSerial GPSSerial(2);

float gpsLat = 0;
float gpsLon = 0;
float gpsSpeed = 0;
float gpsCourse = 0;
bool gpsValid = false;
float speedFiltered = 0;

bool tracking = false;
unsigned long startTime = 0;
float distanceMeters = 0;

float lastLat = 0;
float lastLon = 0;
bool hasLast = false;
float totalDistanceMeters = 0;

#define MAX_POINTS 20
float routeLat[MAX_POINTS];
float routeLon[MAX_POINTS];
int routeSize = 0;
int routeIndex = 0;

String currentNav = "STRAIGHT";
int currentNavDistance = 0;

NimBLECharacteristic *pRx = nullptr;
std::string lastBleValue = "";

#define SERVICE_UUID "12345678-1234-1234-1234-1234567890ab"
#define CHAR_RX_UUID "12345678-1234-1234-1234-1234567890ad"

TFT_eSPI tft = TFT_eSPI();

float haversine(float lat1, float lon1, float lat2, float lon2)
{
  const float R = 6371000;
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

void initGPS()
{
  Serial.println("Initializing GPS on UART2 (RX=16, TX=17)...");
  GPSSerial.begin(9600, SERIAL_8N1, 16, 17);
  Serial.println("✅ GPS Serial initialized at 9600 baud");
}

void updateGPS()
{
  while (GPSSerial.available())
  {
    gps.encode(GPSSerial.read());
  }

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

  if (gps.speed.isValid())
  {
    gpsSpeed = gps.speed.kmph();
    speedFiltered = (speedFiltered * 0.8) + (gpsSpeed * 0.2);
  }

  if (gps.course.isValid())
  {
    gpsCourse = gps.course.deg();
  }

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

  float dist = haversine(gpsLat, gpsLon, targetLat, targetLon);
  currentNavDistance = (int)dist;

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

  float bearing = calculateBearing(gpsLat, gpsLon, targetLat, targetLon);

  currentNav = getTurnDirection(gpsCourse, bearing);
}

void drawUI(float speed, float distance, unsigned long elapsed)
{
  tft.fillScreen(TFT_BLACK);

  tft.setTextColor(TFT_WHITE);
  tft.setTextSize(2);
  tft.drawString("SPEED: " + String(speed, 1) + " km/h", 10, 20);

  tft.drawString("DIST: " + String(distance / 1000.0, 2) + " km", 10, 60);

  int m = elapsed / 60;
  int s = elapsed % 60;
  tft.drawString("TIME: " + String(m) + ":" + (s < 10 ? "0" : "") + String(s), 10, 100);

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

  tft.setTextColor(gpsValid ? TFT_GREEN : TFT_RED);
  tft.setTextSize(1);
  tft.drawString(gpsValid ? "GPS OK" : "NO GPS", 10, 160);

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

  tft.setTextColor(TFT_YELLOW);
  tft.setTextSize(1);
  tft.drawString("NAV: " + currentNav + " in " + String(currentNavDistance) + "m", 10, 200);

  if (routeSize > 0)
  {
    tft.drawString("Wp: " + String(routeIndex) + "/" + String(routeSize), 10, 225);
  }

  tft.setTextColor(TFT_WHITE);
  tft.setTextSize(1);
  if (gpsValid)
  {
    tft.drawString("Lat: " + String(gpsLat, 5), 10, 240);
    tft.drawString("Lon: " + String(gpsLon, 5), 10, 250);
  }
}

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

void setup()
{
  Serial.begin(115200);
  delay(2000);

  Serial.println("\n\n=== BIKE COMPUTER SETUP (REAL GPS) ===\n");

  tft.init();
  tft.setRotation(1);
  tft.fillScreen(TFT_BLACK);
  tft.drawString("BOOTING...", 10, 10);

  initGPS();

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

void loop()
{
  updateGPS();

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

  if (tracking && gpsValid)
  {
    updateDistance();
  }

  updateNavigation();

  unsigned long elapsed = tracking ? (millis() - startTime) / 1000 : 0;
  drawUI(speedFiltered, distanceMeters, elapsed);

  static unsigned long lastBleUpdate = 0;
  if (millis() - lastBleUpdate > 1000) {
    lastBleUpdate = millis();
    if (pRx != nullptr) {
      String statusMsg = "STA:" + String(gpsValid ? "1" : "0") + "," +
                         String(speedFiltered, 1) + "," +
                         String((int)distanceMeters);

      pRx->setValue(statusMsg.c_str());
      pRx->notify();
      Serial.println("BLE PUSH: " + statusMsg);
    }
  }

  delay(100);
}