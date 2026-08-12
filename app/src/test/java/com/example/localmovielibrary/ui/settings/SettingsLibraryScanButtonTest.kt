package com.example.localmovielibrary.ui.settings

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLibraryScanButtonTest {
    @Test
    fun `scanning state keeps dark text on a visible light button`() {
        val palette = libraryScanButtonPalette()

        assertEquals(Color.White.copy(alpha = 0.55f), palette.disabledContainerColor)
        assertEquals(Color.Black.copy(alpha = 0.62f), palette.disabledContentColor)
    }
}
