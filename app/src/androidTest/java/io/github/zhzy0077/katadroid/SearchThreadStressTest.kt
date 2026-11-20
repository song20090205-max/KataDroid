package io.github.zhzy0077.katadroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import io.github.zhzy0077.katadroid.engine.AnalysisPosition
import io.github.zhzy0077.katadroid.engine.BoardSnapshot
import io.github.zhzy0077.katadroid.engine.KataGoModel
import io.github.zhzy0077.katadroid.engine.KataGoSession
import io.github.zhzy0077.katadroid.ui.record.BoardPoint
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Focused regression coverage for Balanced (2-thread) and High Performance
 * (4-thread) CPU search. The small visit budget keeps emulator runtime bounded
 * while still starting the configured search-worker pools.
 */
@RunWith(AndroidJUnit4::class)
class SearchThreadStressTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun position(vararg points: String) =
        AnalysisPosition(points.map { BoardPoint.parse(it).index })

    @Test
    fun balancedAndHighPerformanceSearchRemainStableAcrossRootChanges() {
        val positions = listOf(
            position(),
            position("D4", "Q16", "D16", "Q4"),
        )
        for (threads in listOf(2, 4)) {
            KataGoSession.open(context, Accelerator.CPU, KataGoModel.B6, threads).use { session ->
                for (candidate in positions) {
                    session.setPosition(candidate)
                    val result = session.analyze(64, AtomicBoolean(false))
                    assertNotNull("No result with $threads search threads", result)
                    val analysis = checkNotNull(result)
                    assertTrue("Unexpected position with $threads search threads", analysis.position == candidate)
                    assertTrue("Search did not complete with $threads search threads", analysis.visits >= 64)
                    val board = BoardSnapshot.from(candidate)
                    analysis.candidates.forEach { move ->
                        assertTrue("Illegal candidate with $threads search threads", board.isLegal(move.move))
                        BoardSnapshot.from(candidate.after(move.pv))
                    }
                }
            }
        }
    }

    @Test
    fun fourThreadSearchCancelsAndCanResume() {
        KataGoSession.open(context, Accelerator.CPU, KataGoModel.B6, 4).use { session ->
            val candidate = position()
            session.setPosition(candidate)
            val cancelled = AtomicBoolean(false)
            val running = Thread {
                session.analyze(5_000, cancelled)
            }
            running.start()
            Thread.sleep(100)
            cancelled.set(true)
            running.join(5_000)
            assertTrue("Four-thread search did not stop promptly", !running.isAlive)

            session.setPosition(position("D4", "Q16", "D16", "Q4"))
            val resumed = checkNotNull(session.analyze(64, AtomicBoolean(false)))
            assertTrue(resumed.visits >= 64)
        }
    }
}
