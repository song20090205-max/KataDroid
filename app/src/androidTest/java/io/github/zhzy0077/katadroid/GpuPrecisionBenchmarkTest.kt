package io.github.zhzy0077.katadroid

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import io.github.zhzy0077.katadroid.engine.EngineConfig
import io.github.zhzy0077.katadroid.engine.KataGoModel
import io.github.zhzy0077.katadroid.engine.KataGoSession
import io.github.zhzy0077.katadroid.engine.SearchBenchmark
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test

/**
 * Device benchmark only: FP16 is accepted only when the complete model passes
 * the same CPU-reference checks as FP32. This test does not change production defaults.
 */
class GpuPrecisionBenchmarkTest {
    @Test
    fun compareGpuPrecisionsOnEveryInstalledModel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\n$message\n")
        })

        KataGoModel.refresh(context)
        val precisions = listOf(
            CompiledModel.GpuOptions.Precision.FP32,
            CompiledModel.GpuOptions.Precision.FP16,
            CompiledModel.GpuOptions.Precision.FP16_WITH_FP32_ACCUM,
        )
        var accepted = 0
        for (model in KataGoModel.entries) {
            for (precision in precisions) {
                val started = SystemClock.elapsedRealtimeNanos()
                try {
                    KataGoSession.open(
                        context = context,
                        accelerator = Accelerator.GPU,
                        model = model,
                        gpuPrecision = precision,
                    ).use { session ->
                        val initialization = SystemClock.elapsedRealtimeNanos() - started
                        val result = checkNotNull(SearchBenchmark.run(
                            session = session,
                            config = EngineConfig(model, io.github.zhzy0077.katadroid.engine.EngineBackend.GPU),
                            visitsPerPosition = 100,
                            initializationNanos = initialization,
                            cancelled = AtomicBoolean(false),
                        ))
                        check(result.visits == 300)
                        accepted++
                        report("GPU_PRECISION_ACCEPTED model=${model.id} precision=${precision.name} " +
                            "visitsPerSecond=${"%.2f".format(java.util.Locale.ROOT, result.visitsPerSecond)} " +
                            "initMs=${initialization / 1_000_000.0}")
                    }
                } catch (error: Exception) {
                    report("GPU_PRECISION_REJECTED model=${model.id} precision=${precision.name} " +
                        "reason=${error.javaClass.simpleName}: ${error.message}")
                }
            }
        }
        report("GPU_PRECISION_SUMMARY accepted=$accepted tested=${KataGoModel.entries.size * precisions.size}")
    }
}
