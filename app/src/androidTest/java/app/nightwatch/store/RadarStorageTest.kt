package app.nightwatch.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadarStorageTest {
    @Test fun historySurvivesRestartIsEncryptedAndIsolatedPerAccount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefsName = "nightwatch_radar_storage_test"
        val prefs = context.getSharedPreferences(prefsName, 0)
        val offer = StoreOffer("test-skin", "Synthetic test skin", "", WeaponType.VANDAL, "", 1775, emptyList())
        val account = LiveStoreAccount("Synthetic#test", "EU",
            StoreSnapshot(listOf(offer), System.currentTimeMillis()/1000+3600, false, false),
            null, null, accountId = "synthetic-account-id")
        try {
            RadarRepository(context, prefsName).record(account)
            val restarted = RadarRepository(context, prefsName)
            assertEquals(offer, restarted.read(account).single().offers.single())
            assertEquals(1, restarted.read(account.copy(name="Renamed#test")).size)
            assertTrue(restarted.read(account.copy(accountId="other-account")).isEmpty())
            assertTrue(restarted.read(account.copy(accountId=null)).isEmpty())
            assertFalse(prefs.all.toString().contains("Synthetic test skin"))
            restarted.record(account.copy(accountId="other-account"))
            restarted.clear(account)
            assertTrue(restarted.read(account).isEmpty())
            assertEquals(1, restarted.read(account.copy(accountId="other-account")).size)
        } finally { prefs.edit().clear().commit() }
    }
}
