package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ble.SchoolBellBleManager
import com.example.model.BleState
import com.example.model.NextBellCountdown
import com.example.model.ScheduleSlot
import com.example.model.SchoolBellSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class AddEditDialogState(
    val isOpen: Boolean = false,
    val editingIndex: Int? = null,
    val hour: Int = 8,
    val minute: Int = 0,
    val durationSeconds: Int = 5,
    val enabled: Boolean = true
)

data class DeleteDialogState(
    val isOpen: Boolean = false,
    val index: Int = 0,
    val timeFormatted: String = ""
)

class SchoolBellViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = SchoolBellBleManager(application.applicationContext, viewModelScope)

    val bleState: StateFlow<BleState> = bleManager.bleState
    val schedules: StateFlow<List<ScheduleSlot>> = bleManager.schedules
    val settings: StateFlow<SchoolBellSettings> = bleManager.settings
    val isRelayRinging: StateFlow<Boolean> = bleManager.isRelayRinging
    val toastEvents = bleManager.toastEvents

    // Live Clock State (Updated every 1000ms)
    private val _currentTimeFormatted = MutableStateFlow("")
    val currentTimeFormatted: StateFlow<String> = _currentTimeFormatted.asStateFlow()

    private val _currentDateFormatted = MutableStateFlow("")
    val currentDateFormatted: StateFlow<String> = _currentDateFormatted.asStateFlow()

    // Dialog & Sheet States
    private val _addEditDialog = MutableStateFlow(AddEditDialogState())
    val addEditDialog: StateFlow<AddEditDialogState> = _addEditDialog.asStateFlow()

    private val _deleteDialog = MutableStateFlow(DeleteDialogState())
    val deleteDialog: StateFlow<DeleteDialogState> = _deleteDialog.asStateFlow()

    private val _showSettingsSheet = MutableStateFlow(false)
    val showSettingsSheet: StateFlow<Boolean> = _showSettingsSheet.asStateFlow()

    private val _showPinoutSheet = MutableStateFlow(false)
    val showPinoutSheet: StateFlow<Boolean> = _showPinoutSheet.asStateFlow()

    // Next Bell Countdown combined state
    private val _tickCounter = MutableStateFlow(0L)

    val nextBellCountdown: StateFlow<NextBellCountdown?> = combine(
        _tickCounter,
        schedules,
        settings
    ) { _, currentSchedules, currentSettings ->
        calculateNextBell(currentSchedules, currentSettings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        startLiveClock()
        // Auto connect on launch
        viewModelScope.launch {
            delay(300)
            bleManager.startAutoConnect()
        }
    }

    private fun startLiveClock() {
        viewModelScope.launch {
            val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val arabicDateFormat = SimpleDateFormat("EEEE dd/MM/yyyy", Locale("ar"))

            while (isActive) {
                val now = Date()
                _currentTimeFormatted.value = timeFormat.format(now)
                _currentDateFormatted.value = arabicDateFormat.format(now)
                _tickCounter.value += 1
                delay(1000)
            }
        }
    }

    private fun calculateNextBell(
        currentSchedules: List<ScheduleSlot>,
        currentSettings: SchoolBellSettings
    ): NextBellCountdown? {
        if (currentSchedules.isEmpty() || !currentSettings.systemEnabled) {
            return NextBellCountdown(
                nextSlot = null,
                formattedTargetTime = "--:--",
                remainingHours = 0,
                remainingMinutes = 0,
                remainingSeconds = 0,
                isToday = false,
                message = if (!currentSettings.systemEnabled) "النظام متوقف حاليًا" else "لا توجد أوقات مضافة"
            )
        }

        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        val currentMinute = cal.get(Calendar.MINUTE)
        val currentSecond = cal.get(Calendar.SECOND)
        val currentDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // 1=Sun .. 7=Sat
        val nowTotalSeconds = currentHour * 3600 + currentMinute * 60 + currentSecond

        val enabledSchedules = currentSchedules.filter { it.enabled }.sorted()
        if (enabledSchedules.isEmpty()) {
            return NextBellCountdown(
                nextSlot = null,
                formattedTargetTime = "--:--",
                remainingHours = 0,
                remainingMinutes = 0,
                remainingSeconds = 0,
                isToday = false,
                message = "جميع أوقات الجرس معطلة"
            )
        }

        val isTodayOperating = currentSettings.isDayEnabled(currentDayOfWeek)

        if (isTodayOperating) {
            // Find earliest today
            val todayUpcoming = enabledSchedules.firstOrNull { slot ->
                (slot.hour * 3600 + slot.minute * 60) > nowTotalSeconds
            }

            if (todayUpcoming != null) {
                val targetTotalSeconds = todayUpcoming.hour * 3600 + todayUpcoming.minute * 60
                val diffSeconds = targetTotalSeconds - nowTotalSeconds
                val hours = (diffSeconds / 3600).toLong()
                val mins = ((diffSeconds % 3600) / 60).toLong()
                val secs = (diffSeconds % 60).toLong()

                return NextBellCountdown(
                    nextSlot = todayUpcoming,
                    formattedTargetTime = todayUpcoming.timeFormatted,
                    remainingHours = hours,
                    remainingMinutes = mins,
                    remainingSeconds = secs,
                    isToday = true,
                    message = "الجرس القادم اليوم"
                )
            }
        }

        // If today is finished or not an operating day, find next operating day
        var daysAhead = 1
        var nextDayCalIndex = (currentDayOfWeek % 7) + 1 // Next day
        while (daysAhead <= 7) {
            if (currentSettings.isDayEnabled(nextDayCalIndex)) {
                val nextSlot = enabledSchedules.first()
                val secondsUntilMidnight = (24 * 3600) - nowTotalSeconds
                val fullDaysSeconds = (daysAhead - 1) * 24 * 3600
                val targetDaySeconds = nextSlot.hour * 3600 + nextSlot.minute * 60
                val totalDiffSeconds = secondsUntilMidnight + fullDaysSeconds + targetDaySeconds

                val hours = (totalDiffSeconds / 3600).toLong()
                val mins = ((totalDiffSeconds % 3600) / 60).toLong()
                val secs = (totalDiffSeconds % 60).toLong()

                val dayName = when (nextDayCalIndex) {
                    7 -> "السبت"
                    1 -> "الأحد"
                    2 -> "الاثنين"
                    3 -> "الثلاثاء"
                    4 -> "الأربعاء"
                    5 -> "الخميس"
                    6 -> "الجمعة"
                    else -> ""
                }

                return NextBellCountdown(
                    nextSlot = nextSlot,
                    formattedTargetTime = nextSlot.timeFormatted,
                    remainingHours = hours,
                    remainingMinutes = mins,
                    remainingSeconds = secs,
                    isToday = false,
                    message = if (daysAhead == 1) "الجرس التالي غداً ($dayName)" else "الجرس التالي يوم $dayName"
                )
            }
            daysAhead++
            nextDayCalIndex = (nextDayCalIndex % 7) + 1
        }

        return NextBellCountdown(
            nextSlot = null,
            formattedTargetTime = "--:--",
            remainingHours = 0,
            remainingMinutes = 0,
            remainingSeconds = 0,
            isToday = false,
            message = "لا توجد أيام تشغيل مفعّلة"
        )
    }

    // =========================================================================
    // User Actions
    // =========================================================================
    fun onOpenAddDialog() {
        if (schedules.value.size >= 10) return
        _addEditDialog.value = AddEditDialogState(
            isOpen = true,
            editingIndex = null,
            hour = 8,
            minute = 0,
            durationSeconds = settings.value.defaultDurationSeconds,
            enabled = true
        )
    }

    fun onOpenEditDialog(index: Int, slot: ScheduleSlot) {
        _addEditDialog.value = AddEditDialogState(
            isOpen = true,
            editingIndex = index,
            hour = slot.hour,
            minute = slot.minute,
            durationSeconds = slot.durationSeconds,
            enabled = slot.enabled
        )
    }

    fun onCloseAddEditDialog() {
        _addEditDialog.value = AddEditDialogState(isOpen = false)
    }

    fun onSaveSchedule(hour: Int, minute: Int, duration: Int, enabled: Boolean) {
        val currentDialog = _addEditDialog.value
        if (currentDialog.editingIndex != null) {
            bleManager.updateSchedule(currentDialog.editingIndex, hour, minute, duration, enabled)
        } else {
            bleManager.addSchedule(hour, minute, duration, enabled)
        }
        onCloseAddEditDialog()
    }

    fun onOpenDeleteDialog(index: Int, slot: ScheduleSlot) {
        _deleteDialog.value = DeleteDialogState(
            isOpen = true,
            index = index,
            timeFormatted = slot.timeFormatted
        )
    }

    fun onCloseDeleteDialog() {
        _deleteDialog.value = DeleteDialogState(isOpen = false)
    }

    fun onConfirmDelete() {
        val index = _deleteDialog.value.index
        bleManager.deleteSchedule(index)
        onCloseDeleteDialog()
    }

    fun onToggleSlotEnabled(index: Int, enabled: Boolean) {
        bleManager.toggleScheduleEnabled(index, enabled)
    }

    fun onTestBell(durationSeconds: Int = settings.value.defaultDurationSeconds) {
        bleManager.testBell(durationSeconds)
    }

    fun onOpenSettingsSheet() {
        _showSettingsSheet.value = true
    }

    fun onCloseSettingsSheet() {
        _showSettingsSheet.value = false
    }

    fun onSaveSettings(newSettings: SchoolBellSettings) {
        bleManager.saveSettings(newSettings)
        onCloseSettingsSheet()
    }

    fun onOpenPinoutSheet() {
        _showPinoutSheet.value = true
    }

    fun onClosePinoutSheet() {
        _showPinoutSheet.value = false
    }

    fun onReconnect() {
        bleManager.startAutoConnect()
    }

    fun onManualTimeSync() {
        bleManager.performAutomaticTimeSync()
    }
}
