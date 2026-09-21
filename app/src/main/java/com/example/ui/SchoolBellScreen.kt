package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BleState
import com.example.model.NextBellCountdown
import com.example.model.SYSTEM_DAYS
import com.example.model.ScheduleSlot
import com.example.model.SchoolBellSettings
import com.example.ui.theme.AmberDark
import com.example.ui.theme.AmberPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.RoseError
import com.example.viewmodel.AddEditDialogState
import com.example.viewmodel.DeleteDialogState
import com.example.viewmodel.SchoolBellViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchoolBellScreen(
    viewModel: SchoolBellViewModel,
    modifier: Modifier = Modifier
) {
    val bleState by viewModel.bleState.collectAsState()
    val schedules by viewModel.schedules.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val nextBell by viewModel.nextBellCountdown.collectAsState()
    val isRelayRinging by viewModel.isRelayRinging.collectAsState()
    val currentTime by viewModel.currentTimeFormatted.collectAsState()
    val currentDate by viewModel.currentDateFormatted.collectAsState()

    val addEditState by viewModel.addEditDialog.collectAsState()
    val deleteState by viewModel.deleteDialog.collectAsState()
    val showSettingsSheet by viewModel.showSettingsSheet.collectAsState()
    val showPinoutSheet by viewModel.showPinoutSheet.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Right-To-Left UI for native Arabic experience
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.statusBars,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                BottomActionBar(
                    onOpenSettings = { viewModel.onOpenSettingsSheet() },
                    onTestBell = { viewModel.onTestBell() },
                    onOpenPinout = { viewModel.onOpenPinoutSheet() },
                    isRinging = isRelayRinging
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Top Header & Clock Section
                item {
                    HeaderSection(
                        currentTime = currentTime,
                        currentDate = currentDate,
                        bleState = bleState,
                        onReconnect = { viewModel.onReconnect() },
                        onSyncTime = { viewModel.onManualTimeSync() }
                    )
                }

                // 2. Next Bell Hero Countdown Card
                item {
                    NextBellCard(
                        countdown = nextBell,
                        isRelayRinging = isRelayRinging
                    )
                }

                // 3. Schedule Table Section Header & Count
                item {
                    ScheduleHeaderSection(
                        currentCount = schedules.size,
                        maxCount = 10,
                        onAddClicked = { viewModel.onOpenAddDialog() }
                    )
                }

                // 4. List of Schedule items (up to 10)
                if (schedules.isEmpty()) {
                    item {
                        EmptyScheduleCard(onAddClicked = { viewModel.onOpenAddDialog() })
                    }
                } else {
                    itemsIndexed(schedules) { index, slot ->
                        ScheduleItemCard(
                            index = index,
                            slot = slot,
                            onToggle = { enabled -> viewModel.onToggleSlotEnabled(index, enabled) },
                            onEdit = { viewModel.onOpenEditDialog(index, slot) },
                            onDelete = { viewModel.onOpenDeleteDialog(index, slot) }
                        )
                    }
                }

                // Add button if list has space (less than 10)
                if (schedules.isNotEmpty() && schedules.size < 10) {
                    item {
                        OutlinedButton(
                            onClick = { viewModel.onOpenAddDialog() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("add_schedule_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = AmberDark
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, AmberDark.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "إضافة موعد")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "＋ إضافة وقت جرس (${schedules.size}/10)",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }

                if (schedules.size >= 10) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "تم الوصول إلى الحد الأقصى للمواعيد (10/10)",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        // Dialogs
        if (addEditState.isOpen) {
            AddEditScheduleDialog(
                state = addEditState,
                onDismiss = { viewModel.onCloseAddEditDialog() },
                onSave = { h, m, dur, en -> viewModel.onSaveSchedule(h, m, dur, en) }
            )
        }

        if (deleteState.isOpen) {
            DeleteConfirmDialog(
                state = deleteState,
                onDismiss = { viewModel.onCloseDeleteDialog() },
                onConfirm = { viewModel.onConfirmDelete() }
            )
        }

        if (showSettingsSheet) {
            SettingsModalSheet(
                settings = settings,
                onDismiss = { viewModel.onCloseSettingsSheet() },
                onSave = { newSettings -> viewModel.onSaveSettings(newSettings) }
            )
        }

        if (showPinoutSheet) {
            HardwarePinoutModalSheet(
                onDismiss = { viewModel.onClosePinoutSheet() }
            )
        }
    }
}

@Composable
fun HeaderSection(
    currentTime: String,
    currentDate: String,
    bleState: BleState,
    onReconnect: () -> Unit,
    onSyncTime: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = AmberDark,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SIMPLE SMART SCHOOL BELL",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Large Digital Clock
            Text(
                text = currentTime.ifEmpty { "--:--:--" },
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )

            // Live Date
            Text(
                text = currentDate.ifEmpty { "..." },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(10.dp))

            // Connection and Sync Status Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ConnectionStatusBadge(bleState = bleState, onClick = onReconnect)
                SyncStatusBadge(bleState = bleState, onClick = onSyncTime)
            }
        }
    }
}

@Composable
fun ConnectionStatusBadge(
    bleState: BleState,
    onClick: () -> Unit
) {
    val (color, text) = when (bleState) {
        is BleState.Connected, is BleState.Synced -> Pair(
            EmeraldSuccess,
            "🟢 ESP32 متصل"
        )
        is BleState.Scanning -> Pair(
            AmberPrimary,
            "🟡 جاري البحث..."
        )
        is BleState.Connecting -> Pair(
            AmberPrimary,
            "🟡 جاري الاتصال..."
        )
        is BleState.SyncingTime -> Pair(
            EmeraldSuccess,
            "🟢 ESP32 متصل"
        )
        is BleState.Disconnected -> Pair(
            RoseError,
            "🔴 ESP32 غير متصل"
        )
        is BleState.Error -> Pair(
            RoseError,
            "🔴 انقطع الاتصال"
        )
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        modifier = Modifier.testTag("ble_status_chip")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
fun SyncStatusBadge(
    bleState: BleState,
    onClick: () -> Unit
) {
    val (color, text) = when (bleState) {
        is BleState.Synced -> Pair(EmeraldSuccess, "✓ تمت مزامنة الساعة")
        is BleState.SyncingTime -> Pair(AmberPrimary, "⏳ جاري المزامنة...")
        is BleState.Error -> Pair(RoseError, "⚠ فشلت المزامنة")
        else -> Pair(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), "ساعة الهاتف نشطة")
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.testTag("sync_status_chip")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}

@Composable
fun NextBellCard(
    countdown: NextBellCountdown?,
    isRelayRinging: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bell_ringing")
    val bellRotation by infiniteTransition.animateFloat(
        initialValue = -15f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(120),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bell_anim"
    )

    val cardBg = if (isRelayRinging) {
        Brush.horizontalGradient(listOf(AmberDark, AmberPrimary))
    } else {
        Brush.horizontalGradient(
            listOf(
                MaterialTheme.colorScheme.surface,
                MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("next_bell_card"),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .background(cardBg)
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isRelayRinging) "🔔 جرس المدرسة يرن الآن!" else "الجرس القادم",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isRelayRinging) Color.White else MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = countdown?.formattedTargetTime ?: "--:--",
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = if (isRelayRinging) Color.White else MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (countdown?.nextSlot != null) {
                            "متبقي: ${countdown.remainingFormatted} (${countdown.message})"
                        } else {
                            countdown?.message ?: "لا توجد أوقات قادمة"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isRelayRinging) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.secondary
                    )
                }

                // Bell Icon Animation
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRelayRinging) Color.White.copy(alpha = 0.25f)
                            else AmberPrimary.copy(alpha = 0.15f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRelayRinging) Icons.Default.NotificationsActive else Icons.Default.Schedule,
                        contentDescription = "جرس المدرسة",
                        tint = if (isRelayRinging) Color.White else AmberDark,
                        modifier = Modifier
                            .size(32.dp)
                            .rotate(if (isRelayRinging) bellRotation else 0f)
                    )
                }
            }
        }
    }
}

@Composable
fun ScheduleHeaderSection(
    currentCount: Int,
    maxCount: Int,
    onAddClicked: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "جدول رنين الجرس",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (currentCount >= maxCount) RoseError.copy(alpha = 0.15f) else AmberPrimary.copy(alpha = 0.2f)
            ) {
                Text(
                    text = "$currentCount/$maxCount أوقات",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (currentCount >= maxCount) RoseError else AmberDark,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        if (currentCount < maxCount) {
            IconButton(
                onClick = onAddClicked,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(AmberDark)
                    .testTag("add_header_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "إضافة وقت",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
fun ScheduleItemCard(
    index: Int,
    slot: ScheduleSlot,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("schedule_item_$index"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (slot.enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (slot.enabled) 2.dp else 0.dp),
        border = if (slot.enabled) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                 else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Index badge & Time
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (slot.enabled) AmberDark.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (slot.enabled) AmberDark else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = slot.timeFormatted,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold
                        ),
                        color = if (slot.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )

                    Text(
                        text = "مدة الرنين: ${slot.durationSeconds} ثوانٍ",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (slot.enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            // Action controls: Switch, Edit, Delete
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Switch(
                    checked = slot.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = EmeraldSuccess
                    ),
                    modifier = Modifier.testTag("toggle_slot_$index")
                )

                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag("edit_slot_$index")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "تعديل الوقت",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_slot_$index")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "حذف الوقت",
                        tint = RoseError
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyScheduleCard(onAddClicked: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "لا توجد أوقات مضافة في جدول الجرس",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "يمكنك إضافة حتى 10 مواعيد رنين لجرس المدرسة",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onAddClicked,
                colors = ButtonDefaults.buttonColors(containerColor = AmberDark)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("إضافة أول وقت")
            }
        }
    }
}

@Composable
fun BottomActionBar(
    onOpenSettings: () -> Unit,
    onTestBell: () -> Unit,
    onOpenPinout: () -> Unit,
    isRinging: Boolean
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Settings Button
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("settings_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "الإعدادات")
                Spacer(modifier = Modifier.width(6.dp))
                Text("الإعدادات", fontWeight = FontWeight.Bold)
            }

            // Test Bell Button
            Button(
                onClick = onTestBell,
                modifier = Modifier
                    .weight(1.3f)
                    .height(48.dp)
                    .testTag("test_bell_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRinging) EmeraldSuccess else AmberDark
                )
            ) {
                Icon(
                    imageVector = if (isRinging) Icons.Default.NotificationsActive else Icons.Default.Notifications,
                    contentDescription = "اختبار الجرس"
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isRinging) "جاري الرنين..." else "🔔 اختبار الجرس",
                    fontWeight = FontWeight.Bold
                )
            }

            // Pinout and ESP32 Code button
            IconButton(
                onClick = onOpenPinout,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .testTag("pinout_info_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = "مخطط التوصيل والكود",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// =============================================================================
// Dialogs
// =============================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddEditScheduleDialog(
    state: AddEditDialogState,
    onDismiss: () -> Unit,
    onSave: (hour: Int, minute: Int, duration: Int, enabled: Boolean) -> Unit
) {
    var hourText by remember(state.isOpen, state.editingIndex, state.hour) {
        mutableStateOf(String.format(Locale.US, "%02d", state.hour))
    }
    var minuteText by remember(state.isOpen, state.editingIndex, state.minute) {
        mutableStateOf(String.format(Locale.US, "%02d", state.minute))
    }
    var durationText by remember(state.isOpen, state.editingIndex, state.durationSeconds) {
        mutableStateOf(state.durationSeconds.toString())
    }
    var enabledState by remember(state.isOpen, state.editingIndex, state.enabled) {
        mutableStateOf(state.enabled)
    }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    val presetTimes = listOf("08:00", "08:45", "09:30", "10:15", "11:00", "12:00", "13:00")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (state.editingIndex != null) "تعديل وقت الجرس" else "إضافة وقت الجرس",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "اختر أو أدخل وقت الرنين بنظام 24 ساعة:",
                    style = MaterialTheme.typography.bodyMedium
                )

                // Quick Preset Chips
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    presetTimes.forEach { preset ->
                        val parts = preset.split(":")
                        val pHour = parts[0]
                        val pMin = parts[1]
                        val isSelected = hourText == pHour && minuteText == pMin

                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                hourText = pHour
                                minuteText = pMin
                                errorMessage = null
                            },
                            label = { Text(preset, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AmberDark,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                // Time Pickers (Hours & Minutes)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = {
                            if (it.length <= 2 && it.all { char -> char.isDigit() }) {
                                hourText = it
                                errorMessage = null
                            }
                        },
                        label = { Text("الساعة (00-23)") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("hour_input"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )

                    Text(
                        ":",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = minuteText,
                        onValueChange = {
                            if (it.length <= 2 && it.all { char -> char.isDigit() }) {
                                minuteText = it
                                errorMessage = null
                            }
                        },
                        label = { Text("الدقيقة (00-59)") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("minute_input"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }

                // Bell Duration (seconds)
                OutlinedTextField(
                    value = durationText,
                    onValueChange = {
                        if (it.length <= 2 && it.all { char -> char.isDigit() }) {
                            durationText = it
                            errorMessage = null
                        }
                    },
                    label = { Text("مدة الرنين بالثواني (1-30 ثانية)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("duration_input"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                // Enabled Checkbox
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { enabledState = !enabledState },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = enabledState,
                        onCheckedChange = { enabledState = it },
                        colors = CheckboxDefaults.colors(checkedColor = EmeraldSuccess)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تفعيل هذا الوقت في الجدول", fontWeight = FontWeight.SemiBold)
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = RoseError,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val h = hourText.toIntOrNull()
                    val m = minuteText.toIntOrNull()
                    val dur = durationText.toIntOrNull()

                    if (h == null || h !in 0..23) {
                        errorMessage = "الساعة يجب أن تكون بين 0 و 23"
                        return@Button
                    }
                    if (m == null || m !in 0..59) {
                        errorMessage = "الدقيقة يجب أن تكون بين 0 و 59"
                        return@Button
                    }
                    if (dur == null || dur !in 1..30) {
                        errorMessage = "مدة الرنين يجب أن تكون بين 1 و 30 ثانية"
                        return@Button
                    }

                    onSave(h, m, dur, enabledState)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AmberDark),
                modifier = Modifier.testTag("dialog_save_button")
            ) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_cancel_button")
            ) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
fun DeleteConfirmDialog(
    state: DeleteDialogState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("حذف وقت الجرس", fontWeight = FontWeight.Bold) },
        text = {
            Text("هل أنت متأكد من حذف الوقت (${state.timeFormatted}) من جدول جرس المدرسة؟")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = RoseError),
                modifier = Modifier.testTag("confirm_delete_button")
            ) {
                Text("حذف")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsModalSheet(
    settings: SchoolBellSettings,
    onDismiss: () -> Unit,
    onSave: (SchoolBellSettings) -> Unit
) {
    var defaultDurText by remember(settings) { mutableStateOf(settings.defaultDurationSeconds.toString()) }
    var currentSettings by remember(settings) { mutableStateOf(settings) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "⚙️ إعدادات جرس المدرسة",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold
            )

            // Master System Switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("تشغيل نظام الجرس التلقائي", fontWeight = FontWeight.Bold)
                    Text(
                        if (currentSettings.systemEnabled) "النظام يعمل تلقائيًا" else "النظام متوقف مؤقتًا",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                Switch(
                    checked = currentSettings.systemEnabled,
                    onCheckedChange = { currentSettings = currentSettings.copy(systemEnabled = it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = EmeraldSuccess
                    )
                )
            }

            // Default Bell Duration
            OutlinedTextField(
                value = defaultDurText,
                onValueChange = {
                    if (it.length <= 2 && it.all { char -> char.isDigit() }) {
                        defaultDurText = it
                    }
                },
                label = { Text("مدة الرنين الافتراضية (ثوانٍ)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            // Operating Days Selection
            Text(
                text = "أيام تشغيل الجرس الأسبوعية:",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SYSTEM_DAYS.forEach { day ->
                    val isChecked = currentSettings.isDayEnabled(day.calendarIndex)
                    FilterChip(
                        selected = isChecked,
                        onClick = {
                            currentSettings = currentSettings.withDayToggled(day.calendarIndex, !isChecked)
                        },
                        label = { Text(day.nameArabic, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AmberDark,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Button(
                onClick = {
                    val dur = defaultDurText.toIntOrNull() ?: 5
                    val finalSettings = currentSettings.copy(defaultDurationSeconds = dur.coerceIn(1, 30))
                    onSave(finalSettings)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AmberDark)
            ) {
                Text("حفظ الإعدادات في ESP32", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwarePinoutModalSheet(
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "📋 توصيلات Hardware وكود ESP32",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold
            )

            Text(
                text = "التوصيلات النهائية الثابتة (Final Fixed Pinout):",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PinoutRow("DS1302 CLK", "GPIO 22", "(SCLK / CLK)")
                    PinoutRow("DS1302 DAT", "GPIO 21", "(IO / DAT)")
                    PinoutRow("DS1302 RST", "GPIO 19", "(CE / RST)")
                    PinoutRow("Relay IN", "GPIO 26", "(Relay Trigger)")
                    PinoutRow("Built-in LED", "GPIO 2", "(Status Indicator)")
                }
            }

            Text(
                text = "مميزات النظام وبرمجته الصارمة:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BulletPoint("يعمل النظام ذاتياً ومستقلاً تماماً على ESP32 وDS1302 حتى لو أُغلق الهاتف.")
                BulletPoint("Relay يبدأ مغلقاً (OFF) فور إقلاع ESP32 مع حماية Timeout قصوى (30 ثانية).")
                BulletPoint("مزامنة الوقت تلقائية بالكامل فور اتصال البلوتوث BLE مع تحقق مباشر من DS1302.")
                BulletPoint("تخزين 10 مواعيد في ذاكرة NVS غير المتطايرة في ESP32.")
            }

            Spacer(modifier = Modifier.height(6.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("إغلاق")
            }
        }
    }
}

@Composable
fun PinoutRow(label: String, gpio: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = AmberDark.copy(alpha = 0.15f)
            ) {
                Text(
                    text = gpio,
                    fontWeight = FontWeight.ExtraBold,
                    color = AmberDark,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun BulletPoint(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text("• ", fontWeight = FontWeight.Bold, color = AmberDark)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
