package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.ScheduleSlot
import com.example.model.SchoolBellSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("School Bell", appName)
    }

    @Test
    fun `schedule slots sorting ascendingly`() {
        val slots = listOf(
            ScheduleSlot(id = 1, hour = 11, minute = 0),
            ScheduleSlot(id = 2, hour = 8, minute = 0),
            ScheduleSlot(id = 3, hour = 9, minute = 30)
        )
        val sorted = slots.sorted()
        assertEquals(8, sorted[0].hour)
        assertEquals(9, sorted[1].hour)
        assertEquals(11, sorted[2].hour)
    }

    @Test
    fun `operating days mask toggling`() {
        val settings = SchoolBellSettings(operatingDaysMask = 0b01011111) // Friday (6) is off
        assertTrue(settings.isDayEnabled(7)) // Saturday
        assertTrue(settings.isDayEnabled(1)) // Sunday
        assertEquals(false, settings.isDayEnabled(6)) // Friday is disabled
    }
}
