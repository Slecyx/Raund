package app.nightwatch.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class FreshContentAndAlertScheduleTest {
    @Test fun everyKnownWeaponIsDetectedFromLiveSkinNames() {
        assertEquals(WeaponType.BANDIT, weaponTypeFromDisplayName("Reaver Bandit Level 4"))
        assertEquals(WeaponType.VANDAL, weaponTypeFromDisplayName("Champions 2026 Vandal"))
        assertEquals(WeaponType.OUTLAW, weaponTypeFromDisplayName("Aeris Outlaw"))
        assertEquals(WeaponType.MELEE, weaponTypeFromDisplayName("Champions Karambit"))
        assertEquals(WeaponType.OTHER, weaponTypeFromDisplayName("Unknown future item"))
    }

    @Test fun alertWindowsTargetThreeAndNineWithoutImmediateRuns() {
        val zone = ZoneId.of("Europe/Istanbul")
        val atTwo = ZonedDateTime.of(2026, 9, 23, 2, 30, 0, 0, zone)
        val atFour = atTwo.withHour(4).withMinute(0)
        assertEquals(30 * 60_000L, FavoriteAlerts.nextDelayMillis(3, atTwo))
        assertEquals(5 * 60 * 60_000L, FavoriteAlerts.nextDelayMillis(9, atFour))
        assertEquals(23 * 60 * 60_000L, FavoriteAlerts.nextDelayMillis(3, atFour))
    }

    @Test fun eachDailyWindowHasOneStableDeduplicationKey() {
        val date = LocalDate.of(2026, 9, 23)
        assertEquals("2026-09-23-3", FavoriteAlerts.slotKey(date, 3))
        assertEquals("2026-09-23-9", FavoriteAlerts.slotKey(date, 9))
        assertTrue(FavoriteAlerts.slotKey(date, 3) != FavoriteAlerts.slotKey(date, 9))
    }
}
