package io.github.zhzy0077.katadroid.ui

import android.content.Context
import androidx.core.content.edit
import io.github.zhzy0077.katadroid.engine.GoRules
import io.github.zhzy0077.katadroid.engine.MAX_VISITS
import io.github.zhzy0077.katadroid.engine.validKomi
import io.github.zhzy0077.katadroid.engine.EngineBackend
import io.github.zhzy0077.katadroid.engine.EngineConfig
import io.github.zhzy0077.katadroid.engine.KataGoModel

enum class ComputeProfile(val model: KataGoModel, val visits: Int, val searchThreads: Int) {
    POWER_SAVING(KataGoModel.B6, 500, 1),
    BALANCED(KataGoModel.B6, 2000, 2),
    HIGH_PERFORMANCE(KataGoModel.B10, 5000, 4);
    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.name == id } ?: HIGH_PERFORMANCE
    }
}

data class AppPreferences(
    val coordinates: Boolean = true,
    val candidates: Boolean = true,
    val engineEnabled: Boolean = true,
    val maxVisits: Int = 5000,
    val touchOffsetPx: Int = 0,
    val autoBlack: Boolean = false,
    val autoWhite: Boolean = false,
    val rules: GoRules = GoRules.CHINESE,
    val komi: Float = 7.5f,
    val model: KataGoModel = KataGoModel.B10,
    val backend: EngineBackend = EngineBackend.AUTO,
    val showMoveExplanation: Boolean = true,
    val computeProfile: ComputeProfile = ComputeProfile.HIGH_PERFORMANCE,
) {
    val engineConfig get() = EngineConfig(model, backend, computeProfile.searchThreads)
    fun automatic(color: Int) = if (color == 1) autoBlack else autoWhite
    val hasAutomaticPlayer get() = autoBlack || autoWhite
    val showCandidateMoves get() = candidates && !hasAutomaticPlayer

    fun save(context: Context) = context.getSharedPreferences("board_preferences", Context.MODE_PRIVATE).edit {
        putBoolean("coordinates", coordinates); putBoolean("candidates", candidates)
        putBoolean("engine_enabled", engineEnabled)
        putInt("max_visits", maxVisits); putInt("touch_offset_px", touchOffsetPx)
        putBoolean("auto_black", autoBlack); putBoolean("auto_white", autoWhite)
        putString("rules", rules.id); putFloat("komi", komi)
        putString("model", model.id); putString("backend", backend.name)
        putBoolean("show_move_explanation", showMoveExplanation)
        putString("compute_profile", computeProfile.name)
    }

    companion object {
        fun load(context: Context): AppPreferences {
            val p = context.getSharedPreferences("board_preferences", Context.MODE_PRIVATE)
            // One-time migration to the explicitly requested high-compute profile.
            if (p.getInt("compute_profile_version", 0) < 1) {
                p.edit {
                    putString("model", KataGoModel.B10.id)
                    putInt("max_visits", 5000)
                    putInt("compute_profile_version", 1)
                }
            }
            if (p.getInt("compute_profile_version", 0) < 2) {
                p.edit {
                    putString("compute_profile", ComputeProfile.HIGH_PERFORMANCE.name)
                    putInt("compute_profile_version", 2)
                }
            }
            val profile = ComputeProfile.fromId(p.getString("compute_profile", null))
            return AppPreferences(p.getBoolean("coordinates", true), p.getBoolean("candidates", true),
                p.getBoolean("engine_enabled", true),
                p.getInt("max_visits", 5000).coerceIn(1, MAX_VISITS), p.getInt("touch_offset_px", 0).coerceIn(0, 300),
                p.getBoolean("auto_black", false), p.getBoolean("auto_white", false),
                runCatching { GoRules.fromId(p.getString("rules", "chinese")!!) }.getOrDefault(GoRules.CHINESE),
                p.getFloat("komi", 7.5f).takeIf(::validKomi) ?: 7.5f,
                KataGoModel.fromId(p.getString("model", null)), EngineBackend.fromId(p.getString("backend", null)),
                p.getBoolean("show_move_explanation", true), profile)
        }
    }
}
