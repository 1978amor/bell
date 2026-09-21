/*
 * ============================================================================
 *           SIMPLE SMART SCHOOL BELL - ESP32 EMBEDDED FIRMWARE
 * ============================================================================
 * النظام: جرس مدرسي أوتوماتيكي ذكي وبسيط
 * العتاد: ESP32 DevKit + DS1302 RTC + Relay Module + BLE + Android App
 * 
 * ----------------------------------------------------------------------------
 * مخطط التوصيل النهائي الثابت (Fixed Pinout):
 * ----------------------------------------------------------------------------
 * - DS1302 CLK / SCLK  ==>  GPIO 22
 * - DS1302 DAT / IO    ==>  GPIO 21
 * - DS1302 RST / CE    ==>  GPIO 19
 * - Relay IN (الريليه) ==>  GPIO 26
 * - الليد المدمج (LED L)==>  GPIO 2
 * - VCC (DS1302/Relay) ==>  3.3V أو 5V
 * - GND (مشترك)        ==>  GND
 * ----------------------------------------------------------------------------
 * إعدادات الـ Serial Monitor: 115200 Baud
 * ============================================================================
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Preferences.h>
#include <sys/time.h>
#include <time.h>

// ==========================================
// 1. تعاريف الأطراف والثوابت (PIN DEFINITIONS)
// ==========================================
#define DS1302_CLK_PIN     22   // SCLK / CLK
#define DS1302_DAT_PIN     21   // IO / DAT
#define DS1302_RST_PIN     19   // CE / RST
#define RELAY_PIN          26   // مدخل إشارة الريليه
#define STATUS_LED_PIN      2   // الليد المدمج L على لوحة ESP32

// مستوى تفعيل الريليه (اجعله LOW إذا كانت وحدة الريليه لديك Active-LOW)
#define RELAY_ACTIVE_LEVEL   HIGH
#define RELAY_INACTIVE_LEVEL LOW

// حدود الأمان
#define MAX_RELAY_TIMEOUT_MS 30000UL // حماية قصوى: إيقاف الريليه بعد 30 ثانية مهما حدث
#define MAX_SCHEDULES        10      // الحد الأقصى للمواعيد المجدولة (10 أوقات)

// معرفات البلوتوث (BLE UUIDs)
#define BLE_DEVICE_NAME      "SchoolBell-ESP32"
#define SERVICE_UUID         "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHAR_COMMAND_UUID    "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define CHAR_RESPONSE_UUID   "1c95d5e3-d8f7-413a-bf3d-7a2e5d7be87e"

// ==========================================
// 2. هياكل البيانات (DATA STRUCTURES)
// ==========================================
struct DateTime {
    uint16_t year;      // السنة (مثال: 2026)
    uint8_t  month;     // الشهر (1 - 12)
    uint8_t  day;       // اليوم (1 - 31)
    uint8_t  hour;      // الساعة (0 - 23 بنظام 24 ساعة)
    uint8_t  minute;    // الدقيقة (0 - 59)
    uint8_t  second;    // الثانية (0 - 59)
    uint8_t  dayOfWeek; // 1 = الأحد، 2 = الاثنين، ...، 7 = السبت
    bool     valid;
};

struct ScheduleItem {
    bool     enabled;
    uint8_t  hour;       // 0 - 23
    uint8_t  minute;     // 0 - 59
    uint16_t duration;   // مدة الرنين بالثواني (1 - 30)
};

struct SystemSettings {
    uint16_t defaultDuration; // مدة الرنين الافتراضية بالثواني (مثال: 5)
    uint8_t  operatingDays;   // قناع الأيام: bit0=الأحد ... bit6=السبت
    bool     systemEnabled;   // تشغيل/إيقاف النظام كلياً
};

// ==========================================
// 3. المتغيرات العامة (GLOBAL VARIABLES)
// ==========================================
Preferences preferences;

ScheduleItem schedules[MAX_SCHEDULES];
uint8_t scheduleCount = 0;
SystemSettings settings = { 5, 0b01111111, true }; // تفعيل كافة الأيام افتراضياً

DateTime currentRtcTime = { 2026, 1, 1, 8, 0, 0, 1, false };
bool rtcHardwareDetected = false;
bool timeHasBeenSynchronized = false;

// حالة الريليه
bool isRelayActive = false;
unsigned long relayStartTime = 0;
unsigned long relayDurationMs = 0;

// منع تكرار الرنين في نفس الدقيقة
int lastTriggeredDay = -1;
int lastTriggeredHour = -1;
int lastTriggeredMinute = -1;

// حالة الليد
unsigned long lastLedBlinkTime = 0;
bool ledState = false;
unsigned long bleActivityEndTime = 0;

// كائنات البلوتوث
BLEServer* pServer = nullptr;
BLECharacteristic* pCommandChar = nullptr;
BLECharacteristic* pResponseChar = nullptr;
bool deviceConnected = false;
bool oldDeviceConnected = false;

String pendingCommand = "";
bool hasPendingCommand = false;

// تعريفات مسبقة للدوال
void sendBleResponse(const String& msg);
void formatScheduleListResponse();
void triggerRelay(uint16_t durationSec);
void stopRelay();
DateTime getCurrentTime();

// ==========================================
// 4. مشغل DS1302 المباشر (BIT-BANG DRIVER)
// ==========================================
#define REG_SECONDS     0x80
#define REG_MINUTES     0x82
#define REG_HOURS       0x84
#define REG_DATE        0x86
#define REG_MONTH       0x88
#define REG_DAY_OF_WEEK 0x8A
#define REG_YEAR        0x8C
#define REG_WP          0x8E // Write Protect

static uint8_t decToBcd(uint8_t val) {
    return ((val / 10 * 16) + (val % 10));
}

static uint8_t bcdToDec(uint8_t val) {
    return ((val / 16 * 10) + (val % 16));
}

void ds1302WriteByte(uint8_t value) {
    pinMode(DS1302_DAT_PIN, OUTPUT);
    for (int i = 0; i < 8; i++) {
        digitalWrite(DS1302_DAT_PIN, (value & 0x01) ? HIGH : LOW);
        delayMicroseconds(2);
        digitalWrite(DS1302_CLK_PIN, HIGH);
        delayMicroseconds(4);
        digitalWrite(DS1302_CLK_PIN, LOW);
        delayMicroseconds(2);
        value >>= 1;
    }
}

uint8_t ds1302ReadByte() {
    pinMode(DS1302_DAT_PIN, INPUT_PULLUP);
    uint8_t value = 0;
    for (int i = 0; i < 8; i++) {
        int bit = digitalRead(DS1302_DAT_PIN);
        value |= (bit << i);
        digitalWrite(DS1302_CLK_PIN, HIGH);
        delayMicroseconds(4);
        digitalWrite(DS1302_CLK_PIN, LOW);
        delayMicroseconds(4);
    }
    return value;
}

void ds1302WriteRegister(uint8_t reg, uint8_t val) {
    digitalWrite(DS1302_CLK_PIN, LOW);
    digitalWrite(DS1302_RST_PIN, HIGH);
    delayMicroseconds(4);
    ds1302WriteByte(reg);
    ds1302WriteByte(val);
    digitalWrite(DS1302_RST_PIN, LOW);
    digitalWrite(DS1302_CLK_PIN, LOW);
    pinMode(DS1302_DAT_PIN, INPUT_PULLUP);
    delayMicroseconds(4);
}

uint8_t ds1302ReadRegister(uint8_t reg) {
    digitalWrite(DS1302_CLK_PIN, LOW);
    digitalWrite(DS1302_RST_PIN, HIGH);
    delayMicroseconds(4);
    ds1302WriteByte(reg | 0x01);
    uint8_t val = ds1302ReadByte();
    digitalWrite(DS1302_RST_PIN, LOW);
    digitalWrite(DS1302_CLK_PIN, LOW);
    pinMode(DS1302_DAT_PIN, INPUT_PULLUP);
    delayMicroseconds(4);
    return val;
}

void initDS1302() {
    pinMode(DS1302_CLK_PIN, OUTPUT);
    pinMode(DS1302_RST_PIN, OUTPUT);
    pinMode(DS1302_DAT_PIN, INPUT_PULLUP);

    digitalWrite(DS1302_CLK_PIN, LOW);
    digitalWrite(DS1302_RST_PIN, LOW);

    // إلغاء قفل الحماية من الكتابة
    ds1302WriteRegister(REG_WP, 0x00);

    // التأكد من تشغيل مذبذب الساعة (CH = 0)
    uint8_t sec = ds1302ReadRegister(REG_SECONDS);
    if (sec & 0x80) {
        ds1302WriteRegister(REG_SECONDS, sec & 0x7F);
    }

    // اختبار الاتصال
    uint8_t testMonth = ds1302ReadRegister(REG_MONTH);
    if (testMonth >= 1 && testMonth <= 0x12) {
        rtcHardwareDetected = true;
        Serial.println("[DS1302] ✓ تم التعرف على شريحة RTC بنجاح.");
    } else {
        Serial.println("[DS1302] ⚠ شريحة RTC غير متصلة (سيتم استخدام ساعة ESP32 الداخلية حتى تتم المزامنة).");
    }
}

DateTime readDS1302() {
    DateTime dt;
    dt.valid = false;

    uint8_t secReg   = ds1302ReadRegister(REG_SECONDS);
    uint8_t minReg   = ds1302ReadRegister(REG_MINUTES);
    uint8_t hourReg  = ds1302ReadRegister(REG_HOURS);
    uint8_t dateReg  = ds1302ReadRegister(REG_DATE);
    uint8_t monReg   = ds1302ReadRegister(REG_MONTH);
    uint8_t dowReg   = ds1302ReadRegister(REG_DAY_OF_WEEK);
    uint8_t yearReg  = ds1302ReadRegister(REG_YEAR);

    dt.second    = bcdToDec(secReg & 0x7F);
    dt.minute    = bcdToDec(minReg & 0x7F);
    
    // نظام 24 ساعة
    if (hourReg & 0x80) {
        uint8_t h = bcdToDec(hourReg & 0x1F);
        if (hourReg & 0x20) h += 12; // PM
        dt.hour = h;
    } else {
        dt.hour  = bcdToDec(hourReg & 0x3F);
    }
    dt.day       = bcdToDec(dateReg & 0x3F);
    dt.month     = bcdToDec(monReg & 0x1F);
    dt.dayOfWeek = bcdToDec(dowReg & 0x07);
    dt.year      = 2000 + bcdToDec(yearReg);

    // التحقق من صحة النطاقات
    if (dt.year >= 2024 && dt.year <= 2099 &&
        dt.month >= 1 && dt.month <= 12 &&
        dt.day >= 1 && dt.day <= 31 &&
        dt.hour <= 23 && dt.minute <= 59 && dt.second <= 59 &&
        dt.dayOfWeek >= 1 && dt.dayOfWeek <= 7) {
        dt.valid = true;
    }

    return dt;
}

bool writeDS1302(const DateTime& dt) {
    if (dt.year < 2000 || dt.month < 1 || dt.month > 12 ||
        dt.day < 1 || dt.day > 31 || dt.hour > 23 ||
        dt.minute > 59 || dt.second > 59 || dt.dayOfWeek < 1 || dt.dayOfWeek > 7) {
        return false;
    }

    ds1302WriteRegister(REG_WP, 0x00);
    ds1302WriteRegister(REG_SECONDS, decToBcd(dt.second) & 0x7F);
    ds1302WriteRegister(REG_MINUTES, decToBcd(dt.minute) & 0x7F);
    ds1302WriteRegister(REG_HOURS,   decToBcd(dt.hour) & 0x3F);
    ds1302WriteRegister(REG_DATE,    decToBcd(dt.day) & 0x3F);
    ds1302WriteRegister(REG_MONTH,   decToBcd(dt.month) & 0x1F);
    ds1302WriteRegister(REG_DAY_OF_WEEK, decToBcd(dt.dayOfWeek) & 0x07);
    ds1302WriteRegister(REG_YEAR,    decToBcd(dt.year % 100));

    delay(10);
    return true;
}

// ==========================================
// 5. محرك التوقيت الهجين (HYBRID TIME ENGINE)
// ==========================================
void setSystemClock(const DateTime& dt) {
    // 1. الكتابة في شريحة DS1302
    writeDS1302(dt);

    // 2. ضبط ساعة ESP32 الداخلية الدقيقة
    struct tm t = {0};
    t.tm_year = dt.year - 1900;
    t.tm_mon  = dt.month - 1;
    t.tm_mday = dt.day;
    t.tm_hour = dt.hour;
    t.tm_min  = dt.minute;
    t.tm_sec  = dt.second;
    t.tm_wday = (dt.dayOfWeek % 7);

    time_t epoch = mktime(&t);
    struct timeval tv = { epoch, 0 };
    settimeofday(&tv, NULL);

    timeHasBeenSynchronized = true;
    currentRtcTime = dt;
    currentRtcTime.valid = true;

    Serial.printf("[TIME SYNC] ✓ تم ضبط وتحديث الوقت: %04d-%02d-%02d %02d:%02d:%02d\n",
                  dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second);
}

DateTime getCurrentTime() {
    // الأولوية 1: القراءة من شريحة DS1302 الفيزيائية
    DateTime rtc = readDS1302();
    if (rtc.valid) {
        currentRtcTime = rtc;
        return rtc;
    }

    // الأولوية 2: القراءة من ساعة ESP32 الداخلية المزامنة
    time_t now = time(nullptr);
    struct tm* t = localtime(&now);

    DateTime dt;
    dt.year      = t->tm_year + 1900;
    dt.month     = t->tm_mon + 1;
    dt.day       = t->tm_mday;
    dt.hour      = t->tm_hour;
    dt.minute    = t->tm_min;
    dt.second    = t->tm_sec;
    dt.dayOfWeek = (t->tm_wday == 0) ? 1 : (t->tm_wday + 1);
    dt.valid     = timeHasBeenSynchronized || (dt.year >= 2024);

    currentRtcTime = dt;
    return dt;
}

// ==========================================
// 6. الحفظ في الذاكرة الدائمة (NVS PREFERENCES)
// ==========================================
void sortSchedules() {
    for (int i = 0; i < scheduleCount - 1; i++) {
        for (int j = 0; j < scheduleCount - i - 1; j++) {
            int t1 = schedules[j].hour * 60 + schedules[j].minute;
            int t2 = schedules[j + 1].hour * 60 + schedules[j + 1].minute;
            if (t1 > t2) {
                ScheduleItem temp = schedules[j];
                schedules[j] = schedules[j + 1];
                schedules[j + 1] = temp;
            }
        }
    }
}

void loadSettingsFromNVS() {
    preferences.begin("school_bell", false);

    settings.defaultDuration = preferences.getUShort("def_dur", 5);
    settings.operatingDays   = preferences.getUChar("days_mask", 0b01111111);
    settings.systemEnabled   = preferences.getBool("sys_en", true);

    scheduleCount = preferences.getUChar("sch_count", 0);
    if (scheduleCount > MAX_SCHEDULES) scheduleCount = 0;

    for (int i = 0; i < scheduleCount; i++) {
        String keyPrefix = "s_" + String(i) + "_";
        schedules[i].hour     = preferences.getUChar((keyPrefix + "h").c_str(), 8);
        schedules[i].minute   = preferences.getUChar((keyPrefix + "m").c_str(), 0);
        schedules[i].duration = preferences.getUShort((keyPrefix + "d").c_str(), 5);
        schedules[i].enabled  = preferences.getBool((keyPrefix + "e").c_str(), true);
    }

    // إنشاء مواعيد نموذجية تلقائياً عند أول تشغيل
    if (scheduleCount == 0) {
        scheduleCount = 5;
        uint8_t defaultHours[5]   = {8,  8,  9,  10, 11};
        uint8_t defaultMinutes[5] = {0, 45, 30,  30, 15};
        for (int i = 0; i < 5; i++) {
            schedules[i].hour = defaultHours[i];
            schedules[i].minute = defaultMinutes[i];
            schedules[i].duration = 5;
            schedules[i].enabled = true;
        }
        sortSchedules();
        saveSchedulesToNVS();
    } else {
        sortSchedules();
    }

    preferences.end();
}

void saveSchedulesToNVS() {
    preferences.begin("school_bell", false);
    preferences.putUChar("sch_count", scheduleCount);

    for (int i = 0; i < scheduleCount; i++) {
        String keyPrefix = "s_" + String(i) + "_";
        preferences.putUChar((keyPrefix + "h").c_str(), schedules[i].hour);
        preferences.putUChar((keyPrefix + "m").c_str(), schedules[i].minute);
        preferences.putUShort((keyPrefix + "d").c_str(), schedules[i].duration);
        preferences.putBool((keyPrefix + "e").c_str(), schedules[i].enabled);
    }
    preferences.end();
}

void saveSettingsToNVS() {
    preferences.begin("school_bell", false);
    preferences.putUShort("def_dur", settings.defaultDuration);
    preferences.putUChar("days_mask", settings.operatingDays);
    preferences.putBool("sys_en", settings.systemEnabled);
    preferences.end();
}

// ==========================================
// 7. التحكم بالريليه والليد (RELAY & LED)
// ==========================================
void initHardware() {
    pinMode(RELAY_PIN, OUTPUT);
    digitalWrite(RELAY_PIN, RELAY_INACTIVE_LEVEL);
    isRelayActive = false;

    pinMode(STATUS_LED_PIN, OUTPUT);
    digitalWrite(STATUS_LED_PIN, LOW);
}

void triggerRelay(uint16_t durationSec) {
    if (durationSec == 0) durationSec = settings.defaultDuration;
    if (durationSec > 30) durationSec = 30; // حماية 30 ثانية كحد أقصى

    // تفعيل الريليه والليد المدمج L معاً
    digitalWrite(RELAY_PIN, RELAY_ACTIVE_LEVEL);
    digitalWrite(STATUS_LED_PIN, HIGH);
    isRelayActive = true;
    relayStartTime = millis();
    relayDurationMs = (unsigned long)durationSec * 1000UL;

    Serial.println("**************************************************");
    Serial.printf("🔔 [BELL RINGING] RELAY (GPIO %d) & LED L (GPIO %d) ON for %d SECONDS!\n",
                  RELAY_PIN, STATUS_LED_PIN, durationSec);
    Serial.println("**************************************************");
}

void stopRelay() {
    digitalWrite(RELAY_PIN, RELAY_INACTIVE_LEVEL);
    digitalWrite(STATUS_LED_PIN, LOW);
    isRelayActive = false;
    Serial.println("🔕 [BELL STOPPED] Relay & LED OFF");
}

void relayTask() {
    if (!isRelayActive) return;

    unsigned long elapsed = millis() - relayStartTime;
    if (elapsed >= relayDurationMs || elapsed >= MAX_RELAY_TIMEOUT_MS) {
        stopRelay();
    }
}

void statusLedTask() {
    if (isRelayActive) {
        digitalWrite(STATUS_LED_PIN, HIGH);
        return;
    }

    unsigned long now = millis();
    if (now < bleActivityEndTime) {
        // وميض سريع عند وجود نشاط بلوتوث
        if (now - lastLedBlinkTime >= 100) {
            lastLedBlinkTime = now;
            ledState = !ledState;
            digitalWrite(STATUS_LED_PIN, ledState ? HIGH : LOW);
        }
    } else {
        // نبضة قلب كل ثانية للتأكيد على عمل النظام
        unsigned long phase = now % 1000;
        digitalWrite(STATUS_LED_PIN, (phase < 80) ? HIGH : LOW);
    }
}

// ==========================================
// 8. مهمة الجدولة التلقائية (SCHEDULER TASK)
// ==========================================
void schedulerTask() {
    static unsigned long lastCheck = 0;
    unsigned long now = millis();

    if (now - lastCheck < 500) return; // فحص دوري كل نصف ثانية
    lastCheck = now;

    DateTime dt = getCurrentTime();

    static int lastLoggedMinute = -1;
    if (dt.minute != lastLoggedMinute) {
        lastLoggedMinute = dt.minute;
        Serial.printf("[CLOCK] %04d-%02d-%02d %02d:%02d:%02d | SystemEnabled: %d | Schedules: %d | Relay: %s\n",
                      dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second,
                      settings.systemEnabled ? 1 : 0, scheduleCount, isRelayActive ? "ON" : "OFF");
    }

    if (!settings.systemEnabled) return;

    // فحص يوم التشغيل
    uint8_t dayBit = (dt.dayOfWeek >= 1 && dt.dayOfWeek <= 7) ? (1 << (dt.dayOfWeek - 1)) : 0;
    if (!(settings.operatingDays & dayBit)) {
        return;
    }

    // مقارنة الأوقات المجدولة
    for (int i = 0; i < scheduleCount; i++) {
        if (!schedules[i].enabled) continue;

        if (schedules[i].hour == dt.hour && schedules[i].minute == dt.minute) {
            // منع التكرار في نفس الدقيقة
            if (lastTriggeredDay == dt.day &&
                lastTriggeredHour == dt.hour &&
                lastTriggeredMinute == dt.minute) {
                continue;
            }

            lastTriggeredDay = dt.day;
            lastTriggeredHour = dt.hour;
            lastTriggeredMinute = dt.minute;

            Serial.printf("⏰ [MATCH FOUND] الوقت %02d:%02d يطابق الموعد رقم #%d! جاري تشغيل الجرس...\n",
                          schedules[i].hour, schedules[i].minute, i + 1);
            triggerRelay(schedules[i].duration);
            break;
        }
    }
}

// ==========================================
// 9. استقبال أوامر البلوتوث (BLE HANDLERS)
// ==========================================
class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
        deviceConnected = true;
        bleActivityEndTime = millis() + 3000;
        Serial.println("[BLE] ✓ تم اتصال الهاتف عبر البلوتوث");
    }
    void onDisconnect(BLEServer* pServer) {
        deviceConnected = false;
        Serial.println("[BLE] ✗ انقطع اتصال الهاتف");
    }
};

class CommandCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* pCharacteristic) {
        uint8_t* pData = pCharacteristic->getData();
        size_t len = pCharacteristic->getLength();
        if (pData != nullptr && len > 0) {
            char buffer[128];
            if (len >= sizeof(buffer)) len = sizeof(buffer) - 1;
            memcpy(buffer, pData, len);
            buffer[len] = '\0';
            pendingCommand = String(buffer);
            hasPendingCommand = true;
            bleActivityEndTime = millis() + 1500;
        }
    }
};

void sendBleResponse(const String& msg) {
    if (deviceConnected && pResponseChar != nullptr) {
        pResponseChar->setValue(msg.c_str());
        pResponseChar->notify();
        Serial.printf("[BLE RESP] -> %s\n", msg.c_str());
    }
}

void formatScheduleListResponse() {
    String out = "SCH_LIST:" + String(scheduleCount);
    for (int i = 0; i < scheduleCount; i++) {
        out += ";" + String(schedules[i].hour) + ":" +
               String(schedules[i].minute) + ":" +
               String(schedules[i].duration) + ":" +
               String(schedules[i].enabled ? 1 : 0);
    }
    sendBleResponse(out);
}

void handleCommand(const String& rawCmd) {
    String cmd = rawCmd;
    cmd.trim();
    Serial.printf("[BLE CMD] <- %s\n", cmd.c_str());

    // 0. مسح كافة المواعيد للبدء بمزامنة نظيفة من التطبيق
    if (cmd.equals("CLEAR_TIMES") || cmd.equals("CLEAR_SCHEDULES")) {
        scheduleCount = 0;
        saveSchedulesToNVS();
        sendBleResponse("SCH_OK");
        Serial.println("[BLE CMD] ✓ تم مسح جميع المواعيد القديمة لتطبيق الجدولة الجديدة");
    }
    // 1. مزامنة الوقت: SET_TIME:YYYY,MM,DD,HH,MM,SS,DOW
    else if (cmd.startsWith("SET_TIME:")) {
        String data = cmd.substring(9);
        data.trim();
        int p1 = data.indexOf(',');
        int p2 = data.indexOf(',', p1 + 1);
        int p3 = data.indexOf(',', p2 + 1);
        int p4 = data.indexOf(',', p3 + 1);
        int p5 = data.indexOf(',', p4 + 1);
        int p6 = data.indexOf(',', p5 + 1);

        if (p1 > 0 && p2 > 0 && p3 > 0 && p4 > 0 && p5 > 0 && p6 > 0) {
            DateTime dt;
            dt.year      = data.substring(0, p1).toInt();
            dt.month     = data.substring(p1 + 1, p2).toInt();
            dt.day       = data.substring(p2 + 1, p3).toInt();
            dt.hour      = data.substring(p3 + 1, p4).toInt();
            dt.minute    = data.substring(p4 + 1, p5).toInt();
            dt.second    = data.substring(p5 + 1, p6).toInt();
            dt.dayOfWeek = data.substring(p6 + 1).toInt();

            setSystemClock(dt);

            char buf[64];
            snprintf(buf, sizeof(buf), "TIME_SYNC_OK:%04d-%02d-%02d %02d:%02d:%02d,%d",
                     dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second, dt.dayOfWeek);
            sendBleResponse(String(buf));
        } else {
            sendBleResponse("ERROR:INVALID_TIME_FORMAT");
        }
    }
    // 2. طلب الوقت الحالي: GET_TIME
    else if (cmd.equals("GET_TIME")) {
        DateTime dt = getCurrentTime();
        char buf[64];
        snprintf(buf, sizeof(buf), "TIME:%04d,%02d,%02d,%02d,%02d,%02d,%d",
                 dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second, dt.dayOfWeek);
        sendBleResponse(String(buf));
    }
    // 3. طلب الحالة الكاملة: GET_STATUS
    else if (cmd.equals("GET_STATUS")) {
        DateTime dt = getCurrentTime();
        char buf[80];
        snprintf(buf, sizeof(buf), "STATUS:%d,%d,%d,%d,%d,%d",
                 isRelayActive ? 1 : 0,
                 dt.valid ? 1 : 0,
                 scheduleCount,
                 settings.systemEnabled ? 1 : 0,
                 settings.defaultDuration,
                 settings.operatingDays);
        sendBleResponse(String(buf));
    }
    // 4. طلب جدول الأوقات: GET_SCHEDULE
    else if (cmd.equals("GET_SCHEDULE")) {
        formatScheduleListResponse();
    }
    // 5. إضافة وقت جديد: ADD_TIME:HH,MM,DURATION,ENABLED
    else if (cmd.startsWith("ADD_TIME:")) {
        if (scheduleCount >= MAX_SCHEDULES) {
            sendBleResponse("ERROR:MAX_SCHEDULES_REACHED");
            return;
        }
        String data = cmd.substring(9);
        data.trim();
        int p1 = data.indexOf(',');
        int p2 = data.indexOf(',', p1 + 1);
        int p3 = data.indexOf(',', p2 + 1);

        if (p1 > 0 && p2 > 0 && p3 > 0) {
            uint8_t h   = data.substring(0, p1).toInt();
            uint8_t m   = data.substring(p1 + 1, p2).toInt();
            uint16_t dur = data.substring(p2 + 1, p3).toInt();
            bool en     = (data.substring(p3 + 1).toInt() != 0);

            if (h <= 23 && m <= 59 && dur >= 1 && dur <= 30) {
                schedules[scheduleCount].hour = h;
                schedules[scheduleCount].minute = m;
                schedules[scheduleCount].duration = dur;
                schedules[scheduleCount].enabled = en;
                scheduleCount++;
                sortSchedules();
                saveSchedulesToNVS();
                sendBleResponse("SCH_OK");
                formatScheduleListResponse();
            } else {
                sendBleResponse("ERROR:INVALID_SCHEDULE_DATA");
            }
        } else {
            sendBleResponse("ERROR:INVALID_FORMAT");
        }
    }
    // 6. تعديل وقت: UPDATE_TIME:INDEX,HH,MM,DURATION,ENABLED
    else if (cmd.startsWith("UPDATE_TIME:")) {
        String data = cmd.substring(12);
        data.trim();
        int p0 = data.indexOf(',');
        int p1 = data.indexOf(',', p0 + 1);
        int p2 = data.indexOf(',', p1 + 1);
        int p3 = data.indexOf(',', p2 + 1);

        if (p0 > 0 && p1 > 0 && p2 > 0 && p3 > 0) {
            uint8_t idx = data.substring(0, p0).toInt();
            uint8_t h   = data.substring(p0 + 1, p1).toInt();
            uint8_t m   = data.substring(p1 + 1, p2).toInt();
            uint16_t dur = data.substring(p2 + 1, p3).toInt();
            bool en     = (data.substring(p3 + 1).toInt() != 0);

            if (idx < scheduleCount && h <= 23 && m <= 59 && dur >= 1 && dur <= 30) {
                schedules[idx].hour = h;
                schedules[idx].minute = m;
                schedules[idx].duration = dur;
                schedules[idx].enabled = en;
                sortSchedules();
                saveSchedulesToNVS();
                sendBleResponse("SCH_OK");
                formatScheduleListResponse();
            } else {
                sendBleResponse("ERROR:INVALID_UPDATE_INDEX");
            }
        }
    }
    // 7. حذف وقت: DEL_TIME:INDEX
    else if (cmd.startsWith("DEL_TIME:")) {
        String data = cmd.substring(9);
        data.trim();
        uint8_t idx = data.toInt();
        if (idx < scheduleCount) {
            for (int i = idx; i < scheduleCount - 1; i++) {
                schedules[i] = schedules[i + 1];
            }
            scheduleCount--;
            saveSchedulesToNVS();
            sendBleResponse("SCH_OK");
            formatScheduleListResponse();
        } else {
            sendBleResponse("ERROR:INVALID_DELETE_INDEX");
        }
    }
    // 8. اختبار الجرس الفوري: BELL_TEST:DURATION
    else if (cmd.startsWith("BELL_TEST")) {
        uint16_t dur = settings.defaultDuration;
        if (cmd.startsWith("BELL_TEST:")) {
            dur = cmd.substring(10).toInt();
            if (dur == 0 || dur > 30) dur = settings.defaultDuration;
        }
        triggerRelay(dur);
        sendBleResponse("BELL_TEST_OK:" + String(dur));
    }
    // 9. حفظ الإعدادات: SET_SETTINGS:DEFAULT_DUR,DAYS_MASK,SYS_EN
    else if (cmd.startsWith("SET_SETTINGS:")) {
        String data = cmd.substring(13);
        data.trim();
        int p1 = data.indexOf(',');
        int p2 = data.indexOf(',', p1 + 1);
        if (p1 > 0 && p2 > 0) {
            uint16_t defDur = data.substring(0, p1).toInt();
            uint8_t daysMask = data.substring(p1 + 1, p2).toInt();
            bool sysEn = (data.substring(p2 + 1).toInt() != 0);

            if (defDur >= 1 && defDur <= 30) {
                settings.defaultDuration = defDur;
                settings.operatingDays = daysMask;
                settings.systemEnabled = sysEn;
                saveSettingsToNVS();
                sendBleResponse("SETTINGS_OK");
            } else {
                sendBleResponse("ERROR:INVALID_SETTINGS");
            }
        }
    }
    // 10. طلب الإعدادات: GET_SETTINGS
    else if (cmd.equals("GET_SETTINGS")) {
        char buf[48];
        snprintf(buf, sizeof(buf), "SETTINGS:%d,%d,%d",
                 settings.defaultDuration, settings.operatingDays, settings.systemEnabled ? 1 : 0);
        sendBleResponse(String(buf));
    }
    else {
        sendBleResponse("ERROR:UNKNOWN_COMMAND");
    }
}

// ==========================================
// 10. تهيئة البلوتوث (BLE INITIALIZATION)
// ==========================================
void initBLE() {
    BLEDevice::init(BLE_DEVICE_NAME);
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    BLEService* pService = pServer->createService(SERVICE_UUID);

    pCommandChar = pService->createCharacteristic(
        CHAR_COMMAND_UUID,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
    );
    pCommandChar->setCallbacks(new CommandCallbacks());

    pResponseChar = pService->createCharacteristic(
        CHAR_RESPONSE_UUID,
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
    );
    pResponseChar->addDescriptor(new BLE2902());

    pService->start();

    BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);
    pAdvertising->setMinPreferred(0x12);
    BLEDevice::startAdvertising();

    Serial.println("[BLE] ✓ تم بدء بث إشارة البلوتوث باسم: SchoolBell-ESP32");
}

void handleBLE() {
    if (hasPendingCommand) {
        String cmd = pendingCommand;
        hasPendingCommand = false;
        pendingCommand = "";
        handleCommand(cmd);
    }

    if (!deviceConnected && oldDeviceConnected) {
        delay(500);
        pServer->startAdvertising();
        Serial.println("[BLE] إعادة بدء بث البلوتوث...");
        oldDeviceConnected = deviceConnected;
    }
    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = deviceConnected;
    }
}

// ==========================================
// 11. دالتا SETUP و LOOP الرئيسية
// ==========================================
void setup() {
    Serial.begin(115200);
    Serial.println("\n==========================================");
    Serial.println("  SIMPLE SMART SCHOOL BELL - ESP32 Boot");
    Serial.println("==========================================");

    // 1. تهيئة الريليه والليد (الريليه OFF فوراً عند الإقلاع)
    initHardware();

    // 2. تهيئة شريحة DS1302 RTC
    initDS1302();

    // 3. تحميل الجداول والإعدادات من ذاكرة NVS
    loadSettingsFromNVS();

    // 4. تشغيل خدمة البلوتوث BLE
    initBLE();

    Serial.printf("[SYSTEM] ✓ النظام جاهز ومستعد. عدد المواعيد: %d. مدة الرنين: %d ثوانٍ.\n",
                  scheduleCount, settings.defaultDuration);
}

void loop() {
    handleBLE();       // معالجة البلوتوث والأوامر
    schedulerTask();   // فحص الجدولة التلقائية ومطابقة الوقت
    relayTask();       // توقيت الرنين والحماية القصوى (Auto Cutoff)
    statusLedTask();   // إدارة وميض ليد الحالة L
}
