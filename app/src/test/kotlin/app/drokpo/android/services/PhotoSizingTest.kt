package app.drokpo.android.services

import app.drokpo.android.core.PhotoUploader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoSizingTest {
    private val max = PhotoUploader.MAX_DIMENSION_PX

    @Test
    fun constantsMatchIos() {
        assertEquals(1600, PhotoUploader.MAX_DIMENSION_PX)
        assertEquals(80, PhotoUploader.JPEG_QUALITY)
    }

    @Test
    fun neverUpscales() {
        assertNull(PhotoUploader.targetSize(1600, 1200, max))
        assertNull(PhotoUploader.targetSize(1200, 1600, max))
        assertNull(PhotoUploader.targetSize(10, 10, max))
        assertNull(PhotoUploader.targetSize(1600, 1600, max))
    }

    @Test
    fun longestSideBecomesExactlyTheMax() {
        assertEquals(1600 to 1200, PhotoUploader.targetSize(4032, 3024, max))
        assertEquals(1200 to 1600, PhotoUploader.targetSize(3024, 4032, max))
        assertEquals(1600 to 1600, PhotoUploader.targetSize(4000, 4000, max))
        assertEquals(1600 to 900, PhotoUploader.targetSize(1920, 1080, max))
        // 1601 × 1: the short side never collapses to zero.
        assertEquals(1600 to 1, PhotoUploader.targetSize(1601, 1, max))
        // Rounds to the nearest pixel (3000 × 1999 → 1066.13).
        assertEquals(1600 to 1066, PhotoUploader.targetSize(3000, 1999, max))
    }

    @Test
    fun sampleSizeKeepsTheSampleAtLeastTheMax() {
        assertEquals(1, PhotoUploader.sampleSize(1600, 1200, max))
        assertEquals(1, PhotoUploader.sampleSize(3199, 2000, max))
        assertEquals(2, PhotoUploader.sampleSize(3200, 2400, max))
        assertEquals(2, PhotoUploader.sampleSize(4032, 3024, max))
        assertEquals(4, PhotoUploader.sampleSize(8160, 6120, max)) // 50 MP
        for (side in listOf(1601, 2500, 4032, 6400, 9000, 12000)) {
            val sample = PhotoUploader.sampleSize(side, side / 2, max)
            assertTrue("side $side sample $sample drops below the max", side / sample >= max)
            assertTrue("side $side sample $sample is not the largest", side / (sample * 2) < max)
        }
    }
}
