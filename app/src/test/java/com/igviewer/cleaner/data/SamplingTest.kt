package com.igviewer.cleaner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sampling is a memory-safety guard, not a nicety: decoding a 4000x3000 photo at
 * full size is ~48 MB, which is enough to kill the app on a modest phone.
 */
class SamplingTest {

    @Test
    fun aSmallImageIsDecodedAsIs() {
        assertEquals(1, sampleSizeFor(1920, 1080, 2048))
        assertEquals(1, sampleSizeFor(192, 144, 192))
        // One pixel over the limit already costs a halving, because inSampleSize
        // can only be a power of two.
        assertEquals(2, sampleSizeFor(193, 144, 192))
    }

    @Test
    fun theLongestSideDecides() {
        // Portrait and landscape of the same pixel count behave identically.
        assertEquals(2, sampleSizeFor(4000, 3000, 2048))
        assertEquals(2, sampleSizeFor(3000, 4000, 2048))
        assertEquals(4, sampleSizeFor(8000, 6000, 2048))
    }

    @Test
    fun theResultIsAlwaysAPowerOfTwo() {
        var dimension = 1
        while (dimension < 40_000) {
            val sample = sampleSizeFor(dimension, dimension / 2 + 1, 2048)
            assertEquals(0, sample and (sample - 1)) // power of two
            dimension *= 3
        }
    }

    @Test
    fun theDecodedSizeFitsTheLimit() {
        for (dimension in listOf(193, 500, 2049, 4096, 12_000, 100_000)) {
            val sample = sampleSizeFor(dimension, dimension, 2048)
            assertTrue(dimension / sample <= 2048)
            // …and it is not needlessly small: one step coarser would still fit.
            if (sample > 1) assertTrue(dimension / (sample / 2) > 2048)
        }
    }

    @Test
    fun nonsenseInputNeverReachesTheDecoderAsZero() {
        // inSampleSize = 0 throws, so every degenerate case must collapse to 1.
        assertEquals(1, sampleSizeFor(0, 0, 2048))
        assertEquals(1, sampleSizeFor(-5, 100, 2048))
        assertEquals(1, sampleSizeFor(100, 100, 0))
        assertEquals(1, sampleSizeFor(100, 100, -1))
    }
}
