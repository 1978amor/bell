package com.example.model

import java.util.Locale

/**
 * Individual school bell schedule item (Max 10 per system)
 */
data class ScheduleSlot(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val durationSeconds: Int = 5,
    val enabled: Boolean = true
) : Comparable<ScheduleSlot> {
    
    val timeFormatted: String
        get() = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

    val totalMinutes: Int
        get() = hour * 60 + minute

    override fun compareTo(other: ScheduleSlot): Int {
        return this.totalMinutes.compareTo(other.totalMinutes)
    }
}

/**
 * System level settings stored in ESP32 NVS
 */
data class SchoolBellSettings(
    val defaultDurationSeconds: Int = 5,
    // Bitmask: bit0=Sun, bit1=Mon, bit2=Tue, bit3=Wed, bit4=Thu, bit5=Fri, bit6=Sat
    // Default: 0b01011111 (Sat, Sun, Mon, Tue, Wed, Thu enabled, Fri disabled)
    val operatingDaysMask: Int = 0b01011111,
    val systemEnabled: Boolean = true
) {
    fun isDayEnabled(calendarDayOfWeek: Int): Boolean {
        // java.util.Calendar: Sunday=1, Monday=2, ..., Saturday=7
        val bit = 1 shl (calendarDayOfWeek - 1)
        return (operatingDaysMask and bit) != 0
    }

    fun withDayToggled(calendarDayOfWeek: Int, enabled: Boolean): SchoolBellSettings {
        val bit = 1 shl (calendarDayOfWeek - 1)
        val newMask = if (enabled) {
            operatingDaysMask or bit
        } else {
            operatingDaysMask and bit.inv()
        }
        return copy(operatingDaysMask = newMask)
    }
}

data class DayOfWeekOption(
    val calendarIndex: Int, // Calendar.SUNDAY = 1 ... Calendar.SATURDAY = 7
    val nameArabic: String,
    val nameEnglish: String
)

val SYSTEM_DAYS = listOf(
    DayOfWeekOption(7, "السبت", "Saturday"),
    DayOfWeekOption(1, "الأحد", "Sunday"),
    DayOfWeekOption(2, "الاثنين", "Monday"),
    DayOfWeekOption(3, "الثلاثاء", "Tuesday"),
    DayOfWeekOption(4, "الأربعاء", "Wednesday"),
    DayOfWeekOption(5, "الخميس", "Thursday"),
    DayOfWeekOption(6, "الجمعة", "Friday")
)

/**
 * BLE Connection and Synchronization state
 */
sealed class BleState {
    object Disconnected : BleState()
    object Scanning : BleState()
    object Connecting : BleState()
    object Connected : BleState()
    data class SyncingTime(val step: String = "جارٍ مزامنة الوقت مع DS1302...") : BleState()
    data class Synced(val timestamp: String) : BleState()
    data class Error(val message: String) : BleState()
}

/**
 * Next upcoming bell countdown state
 */
data class NextBellCountdown(
    val nextSlot: ScheduleSlot?,
    val formattedTargetTime: String,
    val remainingHours: Long,
    val remainingMinutes: Long,
    val remainingSeconds: Long,
    val isToday: Boolean,
    val message: String
) {
    val remainingFormatted: String
        get() = String.format(Locale.getDefault(), "%02d:%02d:%02d", remainingHours, remainingMinutes, remainingSeconds)
}
