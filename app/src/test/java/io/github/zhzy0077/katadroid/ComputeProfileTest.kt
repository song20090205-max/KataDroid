package io.github.zhzy0077.katadroid

import io.github.zhzy0077.katadroid.engine.EngineConfig
import io.github.zhzy0077.katadroid.ui.ComputeProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ComputeProfileTest {
    @Test fun powerSavingUsesSmallModelAndSingleThread() {
        val profile = ComputeProfile.POWER_SAVING
        assertEquals("b6c96", profile.model.id)
        assertEquals(500, profile.visits)
        assertEquals(1, profile.searchThreads)
    }

    @Test fun balancedUsesModerateBudgetAndTwoThreads() {
        val profile = ComputeProfile.BALANCED
        assertEquals("b6c96", profile.model.id)
        assertEquals(2000, profile.visits)
        assertEquals(2, profile.searchThreads)
    }

    @Test fun highPerformanceUsesLargerModelAndFourThreads() {
        val profile = ComputeProfile.HIGH_PERFORMANCE
        assertEquals("b10c128", profile.model.id)
        assertEquals(5000, profile.visits)
        assertEquals(4, profile.searchThreads)
    }

    @Test fun engineConfigRejectsUnsafeThreadCounts() {
        assertThrows(IllegalArgumentException::class.java) {
            EngineConfig(searchThreads = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineConfig(searchThreads = 5)
        }
    }
}
