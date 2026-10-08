package com.superz.iptvplayer.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 — the settings page's contract, locked against the reference's
 * SettingActivity.getSettingMenuModels + GetSharedAppInfo.getGeneralSettingModel
 * + the dialog defaults (radio_button_24 / radio_button_epg checked).
 */
class VuSettingsContractTest {

    @Test
    fun `seven items in the reference order with stable ids`() {
        val ids = VuSettingsContract.MENU.map { it.id }
        assertEquals(
            listOf(
                "general_setting", "time_format", "epg_time_line",
                "parental_control", "rate_us", "check_update", "language"
            ),
            ids
        )
    }

    @Test
    fun `every item has a label and an icon`() {
        VuSettingsContract.MENU.forEach { item ->
            assertTrue(item.labelRes != 0)
            assertTrue(item.iconRes != 0)
        }
    }

    @Test
    fun `general items match the reference labels and defaults`() {
        val items = VuSettingsContract.GENERAL_ITEMS
        // v1.18.1 — back to the reference's THREE rows: the Auto Save row
        // moved INTO the player's top bar (user feedback — the setting is
        // per-watch content, chosen while watching; the persisted
        // "general_autosave" preference is untouched, only its UI moved).
        assertEquals(3, items.size)
        assertEquals("AutoStart On Boot UP", items[0].label)
        assertEquals(false, items[0].default)
        assertEquals("Show Full EPG", items[1].label)
        assertEquals(true, items[1].default)
        assertEquals("Active Subtitle", items[2].label)
        assertEquals(false, items[2].default)
    }

    @Test
    fun `dialog defaults match the reference checked radios`() {
        assertEquals("24", VuSettingsContract.DEFAULT_TIME_FORMAT)
        assertEquals("only_epg", VuSettingsContract.DEFAULT_EPG_MODE)
    }
}
