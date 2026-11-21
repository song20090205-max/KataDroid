package io.github.zhzy0077.katadroid.engine

import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import kotlinx.coroutines.CancellationException

/**
 * Prefer the fastest reduced-precision GPU mode, but accept it only when the
 * session's full CPU-reference validation succeeds. Retain a full-FP32 GPU
 * option before abandoning the GPU for CPU.
 */
internal fun openValidatedGpuSession(open: (CompiledModel.GpuOptions.Precision) -> AnalysisSession): AnalysisSession {
    val precisions = listOf(
        CompiledModel.GpuOptions.Precision.FP16,
        CompiledModel.GpuOptions.Precision.FP16_WITH_FP32_ACCUM,
        CompiledModel.GpuOptions.Precision.FP32,
    )
    var last: Throwable? = null
    for (precision in precisions) {
        try {
            return open(precision)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            last = error
            Log.w("KataDroidEngine", "GPU precision $precision failed validation or initialization", error)
        } catch (error: LinkageError) {
            last = error
            Log.w("KataDroidEngine", "GPU precision $precision is unavailable", error)
        }
    }
    throw IllegalStateException("No GPU precision passed KataGo reference validation", last)
}

/** No device allowlist: the packaged runtime and model compilation determine support. */
internal fun openSelectedSession(
    config: EngineConfig,
    npuRuntimePackaged: Boolean,
    open: (Accelerator) -> AnalysisSession,
): AnalysisSession {
    if (config.backend != EngineBackend.AUTO) return open(config.accelerator)
    if (npuRuntimePackaged) {
        try {
            return open(Accelerator.NPU)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("KataDroidEngine", "NPU initialization unavailable; AUTO will try GPU", error)
        } catch (error: LinkageError) {
            Log.w("KataDroidEngine", "NPU runtime could not load; AUTO will try GPU", error)
        }
    }
    try {
        // AUTO's session factory requests FP16; reference fixtures gate the backend.
        return open(Accelerator.GPU)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w("KataDroidEngine", "GPU initialization or reference validation unavailable; AUTO is using CPU", error)
    } catch (error: LinkageError) {
        Log.w("KataDroidEngine", "GPU runtime could not load; AUTO is using CPU", error)
    }
    return open(Accelerator.CPU)
}
