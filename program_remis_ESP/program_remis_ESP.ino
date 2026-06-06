#include <TFT_eSPI.h>
#include <NimBLEDevice.h>
#include <TinyGPSPlus.h>
#include <vector>

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
std::vector<float> routeLat;
std::vector<float> routeLon;
int routeIndex = 0;
bool receivingChunkedRoute = false;
int expectedWaypointCount = 0;
String routeDataBuffer = "";
// Safety cap to avoid runaway memory use from malformed transfers
#define MAX_WAYPOINTS 2000

String currentNav = "STRAIGHT";
int currentNavDistance = 0;

// BLE
NimBLECharacteristic *pRx = nullptr;
NimBLECharacteristic *pTx = nullptr;
std::string lastBleValue = "";

#define SERVICE_UUID "12345678-1234-1234-1234-1234567890ab"
#define CHAR_RX_UUID "12345678-1234-1234-1234-1234567890ad"
#define CHAR_TX_UUID "12345678-1234-1234-1234-1234567890ac"

// Display
TFT_eSPI tft = TFT_eSPI();

// ─── PREVIOUS-VALUE CACHE ─────────────────────────────────
// Keeps only changed regions dirty — no full redraws
String prev_speed = "";
String prev_time = "";
String prev_dist = "";
String prev_avg = "";
String prev_arrow = "";
String prev_navText = "";
String prev_navDist = "";
String prev_wpText = "";
String prev_progress = "";
String prev_maxSpd = "";
String prev_heading = "";
String prev_bleStr = "";
String prev_status = "";

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

  if (routeLat.empty())
  {
    currentNav = "NO ROUTE";
    currentNavDistance = 0;
    return;
  }

  if (routeIndex >= (int)routeLat.size())
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

    if (routeIndex >= (int)routeLat.size())
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

// ─── PALETTE ──────────────────────────────────────────────
#define COL_BG 0x0000     // pure black background
#define COL_PANEL 0x0842  // very dark panel bg for contrast
#define COL_BORDER 0x18C3 // dark blue-grey dividers
#define COL_ACCENT 0x275F // cyan-blue  ~#4fc3f7
#define COL_WHITE 0xFFFF
#define COL_GREY 0x8410  // muted text
#define COL_DIM 0xC618   // lighter grey for labels
#define COL_GREEN 0x07E0 // GPS OK / tracking
#define COL_AMBER 0xFD20 // warning
#define COL_RED 0xF800
#define COL_SPEEDTEXT 0xFFFF
#define COL_NAV_BG 0x0011 // arrow box bg

// ─── LAYOUT CONSTANTS ─────────────────────────────────────
// Screen: 320 wide, 480 tall (portrait)
#define SCR_W 320
#define SCR_H 480

// Row Y positions (top edge of each zone)
#define Y_TOPBAR 0 // h=28
#define Y_DIVIDER_1 28
#define Y_SPEED 30 // h=110  (huge number)
#define Y_DIVIDER_2 140
#define Y_METRICS 142 // h=62   (TIME / DIST / AVG)
#define Y_DIVIDER_3 204
#define Y_NAV 206 // h=90   (arrow + turn info)
#define Y_DIVIDER_4 296
#define Y_PROGRESS 298 // h=30   (route bar)
#define Y_DIVIDER_5 328
#define Y_BOTTOM 380 // h=70   (MAX / HEADING / BLE)
#define Y_DIVIDER_6 450
#define Y_STATUS 450 // h=30   (status bar)
// Y_STATUS now reaches the bottom of the screen

// Column dividers for 3-cell metric row
#define COL_DIV_1 106
#define COL_DIV_2 213

// ═══════════════════════════════════════════════════════════
//  STATIC UI  — called once on boot / after full redraw
// ═══════════════════════════════════════════════════════════
void drawStaticUI()
{
  tft.fillScreen(COL_BG);

  // ── Top bar ──────────────────────────────────────────────
  tft.fillRect(0, Y_TOPBAR, SCR_W, 28, COL_PANEL);

  tft.setTextColor(COL_ACCENT, COL_PANEL);
  tft.setTextFont(2); // small bold monospace
  tft.setTextSize(1);
  tft.drawString("BIKENAV", 12, 7);

  // GPS pill outline (content drawn dynamically)
  tft.drawRoundRect(220, 6, 88, 16, 4, COL_BORDER);

  // ── Dividers ─────────────────────────────────────────────
  auto hline = [&](int y)
  {
    tft.drawFastHLine(0, y, SCR_W, COL_BORDER);
  };
  hline(Y_DIVIDER_1);
  hline(Y_DIVIDER_2);
  hline(Y_DIVIDER_3);
  hline(Y_DIVIDER_4);
  hline(Y_DIVIDER_5);
  hline(Y_DIVIDER_6);

  // ── Metric row vertical dividers ─────────────────────────
  tft.drawFastVLine(COL_DIV_1, Y_METRICS, 62, COL_BORDER);
  tft.drawFastVLine(COL_DIV_2, Y_METRICS, 62, COL_BORDER);

  // ── Metric labels (static) ───────────────────────────────
  tft.setTextFont(1);
  tft.setTextSize(1);
  tft.setTextColor(COL_DIM, COL_BG);

  // TIME label — centred in col 0 (0..106)
  tft.drawString("TIME", 33, Y_METRICS + 4);
  // DIST label — centred in col 1 (106..213)
  tft.drawString("DIST", 140, Y_METRICS + 4);
  // AVG label — centred in col 2 (213..320)
  tft.drawString("AVG", 247, Y_METRICS + 4);

  // ── Speed label ──────────────────────────────────────────
  tft.setTextColor(COL_DIM, COL_BG);
  tft.drawString("SPEED", 12, Y_SPEED + 4);
  tft.drawString("KM/H", 12, Y_SPEED + 90);

  // ── Nav section ──────────────────────────────────────────
  // Arrow box border
  tft.drawRoundRect(10, Y_NAV + 8, 74, 74, 6, COL_BORDER);
  tft.fillRoundRect(11, Y_NAV + 9, 72, 72, 6, COL_NAV_BG);

  // "NEXT TURN" label
  tft.setTextColor(COL_DIM, COL_BG);
  tft.drawString("NEXT TURN", 96, Y_NAV + 8);

  // ── Progress bar label ───────────────────────────────────
  tft.setTextColor(COL_DIM, COL_BG);
  tft.drawString("ROUTE PROGRESS", 10, Y_PROGRESS + 4);

  // Progress bar track
  tft.fillRoundRect(10, Y_PROGRESS + 17, 220, 6, 3, COL_BORDER);

  // ── Bottom row background ─────────────────────────────────
  tft.fillRect(0, Y_BOTTOM, SCR_W, Y_STATUS - Y_BOTTOM, COL_PANEL);
  tft.fillRect(0, Y_STATUS, SCR_W, SCR_H - Y_STATUS, COL_PANEL);

  // ── Bottom row dividers ───────────────────────────────────
  tft.drawFastVLine(COL_DIV_1, Y_BOTTOM, Y_STATUS - Y_BOTTOM, COL_BORDER);
  tft.drawFastVLine(COL_DIV_2, Y_BOTTOM, Y_STATUS - Y_BOTTOM, COL_BORDER);

  // Bottom row labels
  tft.setTextColor(COL_DIM, COL_PANEL);
  tft.drawString("MAX KM/H", 12, Y_BOTTOM + 4);
  tft.drawString("HEADING", 116, Y_BOTTOM + 4);
  tft.drawString("BLE", 240, Y_BOTTOM + 4);
}

// ═══════════════════════════════════════════════════════════
//  HELPER — draw arrow SVG-style in the box
// ═══════════════════════════════════════════════════════════
void drawArrow(String dir, bool refresh)
{
  if (dir == prev_arrow && !refresh)
    return;
  prev_arrow = dir;

  int bx = 11, by = Y_NAV + 9, bw = 72, bh = 72;
  tft.fillRect(bx, by, bw, bh, COL_NAV_BG);

  int cx = bx + bw / 2;
  int cy = by + bh / 2;
  uint16_t c = COL_ACCENT;

  if (dir == "STRAIGHT")
  {
    tft.fillTriangle(cx, cy - 24, cx - 14, cy + 6, cx + 14, cy + 6, c);
    tft.fillRect(cx - 5, cy + 4, 10, 18, c);
  }
  else if (dir == "LEFT")
  {
    tft.fillTriangle(cx - 24, cy, cx + 6, cy - 14, cx + 6, cy + 14, c);
    tft.fillRect(cx + 4, cy - 5, 18, 10, c);
  }
  else if (dir == "RIGHT")
  {
    tft.fillTriangle(cx + 24, cy, cx - 6, cy - 14, cx - 6, cy + 14, c);
    tft.fillRect(cx - 22, cy - 5, 18, 10, c);
  }
  else if (dir == "U-TURN")
  {
    tft.fillTriangle(cx, cy + 24, cx - 14, cy - 6, cx + 14, cy - 6, c);
    tft.fillRect(cx - 5, cy - 22, 10, 18, c);
    for (int i = -12; i <= 12; i += 4)
    {
      tft.fillCircle(cx + i, cy - 20, 2, c);
    }
  }
  else if (dir == "ARRIVED")
  {
    tft.fillTriangle(cx - 16, cy, cx - 4, cy + 14, cx + 20, cy - 18, c);
    c = COL_GREEN;
    tft.drawCircle(cx, cy, 28, COL_GREEN);
  }
  else
  {
    tft.fillRect(cx - 20, cy - 3, 40, 6, COL_GREY);
  }
}

// ═══════════════════════════════════════════════════════════
//  GPS PILL  (top-right)
// ═══════════════════════════════════════════════════════════
void drawGpsPill(bool valid, int sats)
{
  String txt = valid ? "GPS OK" : "NO GPS";
  if (txt == prev_status)
    return;
  prev_status = txt;

  tft.fillRoundRect(221, 7, 86, 14, 4, COL_PANEL);
  uint16_t col = valid ? COL_GREEN : COL_AMBER;
  tft.setTextFont(1);
  tft.setTextSize(1);
  tft.setTextColor(col, COL_PANEL);

  tft.fillCircle(230, 14, 3, col);
  tft.drawString(txt, 236, 8);
}

// ═══════════════════════════════════════════════════════════
//  DYNAMIC UI  — called every loop
// ═══════════════════════════════════════════════════════════
void updateDynamicUI(float speed, float distanceM, unsigned long elapsed)
{
  // ── 1. Speed ─────────────────────────────────────────────
  String speedStr = String(speed, 1);
  if (speedStr != prev_speed)
  {
    tft.fillRect(0, Y_SPEED + 14, SCR_W, 72, COL_BG);
    tft.setTextFont(7); // large 7-seg style
    tft.setTextSize(1);
    uint16_t sc = (speed < 5) ? COL_GREY : COL_SPEEDTEXT;
    tft.setTextColor(sc, COL_BG);
    tft.drawCentreString(speedStr, SCR_W / 2, Y_SPEED + 16, 7);
    prev_speed = speedStr;
  }

  // ── 2. Metric row ─────────────────────────────────────────
  int m = elapsed / 60, s = elapsed % 60;
  String timeStr = String(m) + ":" + (s < 10 ? "0" : "") + String(s);
  String distStr = (distanceM < 1000)
                       ? String((int)distanceM) + "m"
                       : String(distanceM / 1000.0, 2) + "k";

  float avg = (elapsed > 0) ? (distanceM / 1000.0f) / (elapsed / 3600.0f) : 0.0f;
  String avgStr = String(avg, 1);

  auto drawMetricVal = [&](String val, String &prev, int x, int w)
  {
    if (val != prev)
    {
      tft.fillRect(x + 4, Y_METRICS + 16, w - 8, 40, COL_BG);
      tft.setTextFont(4);
      tft.setTextSize(1);
      tft.setTextColor(COL_WHITE, COL_BG);
      tft.drawCentreString(val, x + w / 2, Y_METRICS + 22, 4);
      prev = val;
    }
  };

  drawMetricVal(timeStr, prev_time, 0, COL_DIV_1);
  drawMetricVal(distStr, prev_dist, COL_DIV_1, COL_DIV_2 - COL_DIV_1);
  drawMetricVal(avgStr, prev_avg, COL_DIV_2, SCR_W - COL_DIV_2);

  // ── 3. GPS pill ───────────────────────────────────────────
  drawGpsPill(gpsValid, gps.satellites.isValid() ? gps.satellites.value() : 0);

  // ── 4. Nav arrow ──────────────────────────────────────────
  drawArrow(currentNav, false);

  // nav text
  if (currentNav != prev_navText)
  {
    tft.fillRect(96, Y_NAV + 20, 210, 30, COL_BG);
    tft.setTextFont(4);
    tft.setTextSize(1);
    uint16_t nc = (currentNav == "ARRIVED") ? COL_GREEN : COL_ACCENT;
    tft.setTextColor(nc, COL_BG);
    tft.drawString(currentNav, 96, Y_NAV + 22);
    prev_navText = currentNav;
  }

  // nav distance
  String navDistStr = (currentNavDistance > 0)
                          ? "in " + String(currentNavDistance) + " m"
                          : "";
  if (navDistStr != prev_navDist)
  {
    tft.fillRect(96, Y_NAV + 52, 210, 22, COL_BG);
    tft.setTextFont(2);
    tft.setTextSize(1);
    tft.setTextColor(COL_GREY, COL_BG);
    tft.drawString(navDistStr, 96, Y_NAV + 54);
    prev_navDist = navDistStr;
  }

  // waypoint counter
  String wpStr = routeLat.empty()
                     ? "NO ROUTE"
                     : "WP " + String(routeIndex + 1) + " / " + String(routeLat.size());
  if (wpStr != prev_wpText)
  {
    tft.fillRect(96, Y_NAV + 72, 210, 14, COL_BG);
    tft.setTextFont(1);
    tft.setTextSize(1);
    tft.setTextColor(COL_DIM, COL_BG);
    tft.drawString(wpStr, 96, Y_NAV + 74);
    prev_wpText = wpStr;
  }

  // ── 5. Progress bar ───────────────────────────────────────
  int total = routeLat.size();
  int prog = (total > 0) ? map(routeIndex, 0, total, 0, 220) : 0;
  String progStr = String(prog);
  if (progStr != prev_progress)
  {
    tft.fillRect(10, Y_PROGRESS + 17, 220, 6, COL_BORDER);
    if (prog > 0)
      tft.fillRoundRect(10, Y_PROGRESS + 17, prog, 6, 3, COL_ACCENT);
    tft.fillRect(240, Y_PROGRESS + 4, 70, 20, COL_BG);
    tft.setTextFont(1);
    tft.setTextSize(1);
    tft.setTextColor(COL_GREY, COL_BG);
    int pct = (total > 0) ? (routeIndex * 100 / total) : 0;
    tft.drawString(String(pct) + "%", 244, Y_PROGRESS + 14);
    prev_progress = progStr;
  }

  // ── 6. Bottom row ─────────────────────────────────────────
  static float maxSpeed = 0;
  if (speed > maxSpeed)
    maxSpeed = speed;
  String maxStr = String(maxSpeed, 1);
  if (maxStr != prev_maxSpd)
  {
    tft.fillRect(4, Y_BOTTOM + 18, COL_DIV_1 - 8, 28, COL_PANEL);
    tft.setTextFont(4);
    tft.setTextSize(1);
    tft.setTextColor(COL_WHITE, COL_PANEL);
    tft.drawCentreString(maxStr, COL_DIV_1 / 2, Y_BOTTOM + 22, 4);
    prev_maxSpd = maxStr;
  }

  String headStr = String((int)gpsCourse) + "\xB0";
  if (headStr != prev_heading)
  {
    tft.fillRect(COL_DIV_1 + 4, Y_BOTTOM + 18, COL_DIV_2 - COL_DIV_1 - 8, 28, COL_BG);
    tft.setTextFont(4);
    tft.setTextSize(1);
    tft.setTextColor(COL_WHITE, COL_BG);
    tft.drawCentreString(headStr, (COL_DIV_1 + COL_DIV_2) / 2, Y_BOTTOM + 22, 4);
    prev_heading = headStr;
  }

  if (prev_bleStr != "BLE")
  {
    tft.setTextFont(4);
    tft.setTextSize(1);
    tft.setTextColor(COL_GREEN, COL_BG);
    tft.drawCentreString("ON", (COL_DIV_2 + SCR_W) / 2, Y_BOTTOM + 22, 4);
    prev_bleStr = "BLE";
  }

  // ── 7. Status bar ─────────────────────────────────────────
  String trackStr = tracking ? "● TRACKING" : "○ STOPPED";
  uint16_t tc = tracking ? COL_GREEN : COL_GREY;
  static int lastTracking = -1;
  if (lastTracking != (int)tracking)
  {
    tft.fillRect(0, Y_STATUS, SCR_W, SCR_H - Y_STATUS, COL_PANEL);
    tft.setTextFont(1);
    tft.setTextSize(1);
    tft.setTextColor(tc, COL_PANEL);
    tft.drawString(trackStr, 10, Y_STATUS + 9);
    tft.setTextColor(COL_DIM, COL_PANEL);
    tft.drawString("BikeComputer", SCR_W / 2 - 32, Y_STATUS + 9);

    int bx = 270, by = Y_STATUS + 6;
    int heights[] = {4, 7, 11, 15};
    for (int i = 0; i < 4; i++)
    {
      int bh = heights[i];
      uint16_t bc = (gpsValid && i < 3) ? COL_ACCENT : COL_BORDER;
      tft.fillRect(bx + i * 8, by + (15 - bh), 5, bh, bc);
    }
    tft.setTextColor(COL_DIM, COL_PANEL);
    tft.drawString(String(gps.satellites.isValid() ? (int)gps.satellites.value() : 0), 303, Y_STATUS + 9);

    lastTracking = (int)tracking;
  }
}

// ═══════════════════════════════════════════════════════════
//  MAIN DRAW ENTRY — replace your existing drawUI()
// ═══════════════════════════════════════════════════════════
void drawUI(float speed, float distance, unsigned long elapsed)
{
  static bool firstDraw = true;
  if (firstDraw)
  {
    drawStaticUI();
    firstDraw = false;
  }
  updateDynamicUI(speed, distance, elapsed);
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
  else if (value.equalsIgnoreCase("PAUSE"))
  {
    tracking = false;
    Serial.println("✅ TRACKING PAUSED");
  }
  else if (value.equalsIgnoreCase("RESUME"))
  {
    tracking = true;
    Serial.println("✅ TRACKING RESUMED");
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
  else if (value.startsWith("ROUTE_START:"))
  {
    // Start of chunked route transmission
    routeLat.clear();
    routeLon.clear();
    receivingChunkedRoute = true;
    expectedWaypointCount = value.substring(12).toInt();
    routeDataBuffer = "";
    Serial.print("🔄 ROUTE_START: Expecting ");
    Serial.print(expectedWaypointCount);
    Serial.println(" waypoints");

    // Acknowledge route start immediately so sender can begin chunked transfer
    if (pTx != nullptr)
    {
      String ack = "ROUTE_ACK:0";
      pTx->setValue(ack.c_str());
      pTx->notify();
    }
  }
  else if (value.startsWith("ROUTE_DATA:"))
  {
    if (!receivingChunkedRoute)
    {
      Serial.println("⚠️ ROUTE_DATA received before ROUTE_START; ignoring");
      if (pTx != nullptr)
      {
        String err = "ROUTE_ERR:ORDER";
        pTx->setValue(err.c_str());
        pTx->notify();
      }
      return;
    }

    // Chunk of waypoints (append to buffer and parse complete tokens)
    value.remove(0, 11);
    routeDataBuffer += value;

    // Parse complete tokens terminated by ';'
    while (true)
    {
      int sep = routeDataBuffer.indexOf(';');
      if (sep == -1)
        break;
      String part = routeDataBuffer.substring(0, sep);
      routeDataBuffer = routeDataBuffer.substring(sep + 1);

      int comma = part.indexOf(',');
      if (comma > 0)
      {
        routeLat.push_back(part.substring(0, comma).toFloat());
        routeLon.push_back(part.substring(comma + 1).toFloat());
      }

      // safety cap
      if ((int)routeLat.size() >= MAX_WAYPOINTS)
      {
        Serial.println("ERROR: route exceeds MAX_WAYPOINTS, aborting route receive");
        receivingChunkedRoute = false;
        // notify phone of error
        if (pTx != nullptr)
        {
          String err = "ROUTE_ERR:MAX";
          pTx->setValue(err.c_str());
          pTx->notify();
        }
        routeDataBuffer = "";
        break;
      }
    }

    Serial.print("✅ ROUTE_DATA chunk: now have ");
    Serial.print(routeLat.size());
    Serial.println(" waypoints");

    // Send ACK of current count
    if (pTx != nullptr)
    {
      String ack = "ROUTE_ACK:" + String(routeLat.size());
      pTx->setValue(ack.c_str());
      pTx->notify();
    }
  }
  else if (value.equalsIgnoreCase("ROUTE_END") && receivingChunkedRoute)
  {
    // End of chunked route transmission
    // Parse any remaining data in buffer (last token may not have trailing ';')
    if (routeDataBuffer.length() > 0)
    {
      // split remaining by ';' just in case, then parse final piece
      int start = 0;
      while (true)
      {
        int sep = routeDataBuffer.indexOf(';', start);
        String part = (sep == -1) ? routeDataBuffer.substring(start) : routeDataBuffer.substring(start, sep);
        int comma = part.indexOf(',');
        if (comma > 0)
        {
          routeLat.push_back(part.substring(0, comma).toFloat());
          routeLon.push_back(part.substring(comma + 1).toFloat());
        }
        if (sep == -1)
          break;
        start = sep + 1;
      }
      routeDataBuffer = "";
    }

    receivingChunkedRoute = false;
    routeIndex = 0;
    Serial.print("✅ ROUTE COMPLETE: ");
    Serial.print(routeLat.size());
    Serial.print(" waypoints (expected ");
    Serial.print(expectedWaypointCount);
    Serial.println(")");

    // Final ACK
    if (pTx != nullptr)
    {
      String ack = "ROUTE_ACK:" + String(routeLat.size());
      pTx->setValue(ack.c_str());
      pTx->notify();
    }
  }
  else if (value.startsWith("ROUTE:"))
  {
    // Legacy single-packet route (backward compatible)
    routeLat.clear();
    routeLon.clear();
    value.remove(0, 6);

    int start = 0;
    while (true)
    {
      int sep = value.indexOf(';', start);
      String part = (sep == -1) ? value.substring(start) : value.substring(start, sep);

      int comma = part.indexOf(',');
      if (comma > 0)
      {
        routeLat.push_back(part.substring(0, comma).toFloat());
        routeLon.push_back(part.substring(comma + 1).toFloat());
      }

      if (sep == -1)
        break;
      start = sep + 1;
    }

    routeIndex = 0;
    Serial.print("✅ ROUTE RECEIVED: ");
    Serial.print(routeLat.size());
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
  tft.setRotation(0); // portrait orientation for a vertical dashboard
  tft.fillScreen(TFT_RED);
  delay(200);
  tft.fillScreen(TFT_GREEN);
  delay(200);
  tft.fillScreen(TFT_BLUE);
  delay(200);
  tft.fillScreen(TFT_WHITE);
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
          NIMBLE_PROPERTY::WRITE_NR);

  pRx->setValue("READY");

  pTx = service->createCharacteristic(
      CHAR_TX_UUID,
      NIMBLE_PROPERTY::READ |
          NIMBLE_PROPERTY::NOTIFY);
  pTx->setValue("READY");
  pTx->addDescriptor(new NimBLEDescriptor("2902", NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE, 2, pTx));

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
  if (millis() - lastBleUpdate > 1000)
  {
    lastBleUpdate = millis();
    if (pTx != nullptr)
    {
      // Send consolidated status: STA:fix,speed,distance
      String statusMsg = "STA:" + String(gpsValid ? "1" : "0") + "," +
                         String(speedFiltered, 1) + "," +
                         String((int)distanceMeters);

      pTx->setValue(statusMsg.c_str());
      pTx->notify();
    }
  }

  delay(100);
}
