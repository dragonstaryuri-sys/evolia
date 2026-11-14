package me.rerere.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSizeRulesTest {

    // ==== snapToDalle3Size ====

    @Test
    fun `snap exact allowed size returns itself`() {
        assertEquals("1024x1024", ImageSizeRules.snapToDalle3Size(1024, 1024))
        assertEquals("1792x1024", ImageSizeRules.snapToDalle3Size(1792, 1024))
        assertEquals("1024x1792", ImageSizeRules.snapToDalle3Size(1024, 1792))
    }

    @Test
    fun `snap landscape ratio maps to 1792x1024`() {
        // 2:1 横版，最近档为 1792x1024（1.75）
        assertEquals("1792x1024", ImageSizeRules.snapToDalle3Size(2048, 1024))
    }

    @Test
    fun `snap portrait ratio maps to 1024x1792`() {
        // 1:2 竖版，最近档为 1024x1792
        assertEquals("1024x1792", ImageSizeRules.snapToDalle3Size(1024, 2048))
    }

    @Test
    fun `snap 3 to 2 landscape maps to 1792x1024`() {
        // 1.5 比 1.0 更接近 1.75
        assertEquals("1792x1024", ImageSizeRules.snapToDalle3Size(1536, 1024))
    }

    @Test
    fun `snap non positive dimension falls back to square`() {
        assertEquals("1024x1024", ImageSizeRules.snapToDalle3Size(0, 1024))
        assertEquals("1024x1024", ImageSizeRules.snapToDalle3Size(1024, 0))
        assertEquals("1024x1024", ImageSizeRules.snapToDalle3Size(-1, -1))
    }

    // ==== deriveGoogleAspectRatio ====

    @Test
    fun `derive exact ratios`() {
        assertEquals(ImageAspectRatio.SQUARE, ImageSizeRules.deriveGoogleAspectRatio(1024, 1024))
        assertEquals(ImageAspectRatio.LANDSCAPE, ImageSizeRules.deriveGoogleAspectRatio(1920, 1080))
        assertEquals(ImageAspectRatio.PORTRAIT, ImageSizeRules.deriveGoogleAspectRatio(1080, 1920))
    }

    @Test
    fun `derive 4 to 3 maps to SQUARE and 3 to 4 maps to PORTRAIT`() {
        // 4:3（≈1.333）：|1.333-1|=0.333 < |1.333-1.778|=0.444 → SQUARE
        assertEquals(ImageAspectRatio.SQUARE, ImageSizeRules.deriveGoogleAspectRatio(1024, 768))
        // 3:4（0.75）：|0.75-0.5625|=0.1875 < |0.75-1|=0.25 → PORTRAIT
        assertEquals(ImageAspectRatio.PORTRAIT, ImageSizeRules.deriveGoogleAspectRatio(768, 1024))
    }

    @Test
    fun `derive moderate ratios`() {
        // 16:10（1.6）更接近 16:9
        assertEquals(ImageAspectRatio.LANDSCAPE, ImageSizeRules.deriveGoogleAspectRatio(1536, 960))
        // 9:21（≈0.429）介于 9:16（0.5625）与极端竖版之间，仍比 1:1 近
        assertEquals(ImageAspectRatio.PORTRAIT, ImageSizeRules.deriveGoogleAspectRatio(360, 840))
    }

    @Test
    fun `derive non positive dimension falls back to SQUARE`() {
        assertEquals(ImageAspectRatio.SQUARE, ImageSizeRules.deriveGoogleAspectRatio(0, 100))
        assertEquals(ImageAspectRatio.SQUARE, ImageSizeRules.deriveGoogleAspectRatio(100, 0))
    }
}
