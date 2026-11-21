package io.github.zhzy0077.katadroid.engine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/** API credentials are encrypted with a non-exportable Android Keystore key and excluded from backup. */
object CloudTokenStore {
    private const val KEY_ALIAS = "katago-cloud-api-token-v1"
    private const val GCM_IV_BYTES = 12

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "katago-cloud-token.enc"))

    @Synchronized
    fun save(context: Context, token: String) {
        require(token.length in 16..512) { "Cloud API token must contain 16–512 characters" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val atomic = file(context)
        val output = atomic.startWrite()
        try {
            output.write(cipher.iv.size)
            output.write(cipher.iv)
            output.write(encrypted)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    @Synchronized
    fun read(context: Context): String? = runCatching {
        val bytes = file(context).openRead().use { it.readBytes() }
        require(bytes.isNotEmpty())
        val ivSize = bytes[0].toInt() and 0xff
        require(ivSize == GCM_IV_BYTES && bytes.size > 1 + ivSize)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 1 + ivSize)))
        cipher.doFinal(bytes.copyOfRange(1 + ivSize, bytes.size)).toString(Charsets.UTF_8)
    }.getOrNull()

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    fun isConfigured(context: Context) = read(context) != null
}

/** A stateless client for the versioned remote KataGo analysis API. */
class CloudAnalysisSession(
    endpoint: String,
    private val token: String,
) : AnalysisSession {
    private val analyzeUrl: URL = run {
        val base = endpoint.trim().trimEnd('/')
        require(base.startsWith("https://", ignoreCase = true)) { "Cloud endpoint must use HTTPS" }
        val parsed = URL("$base/v1/analyze")
        require(parsed.protocol.equals("https", true) && parsed.host.isNotBlank() &&
            parsed.userInfo == null && parsed.query == null && parsed.ref == null) {
            "Invalid HTTPS cloud endpoint"
        }
        parsed
    }

    private var position: AnalysisPosition? = null
    override val backend: String = "CLOUD"
    override val supportsTimedAnalysis: Boolean = false

    init {
        require(token.length >= 16) { "Cloud API token is not configured" }
    }

    override fun setPosition(position: AnalysisPosition) {
        this.position = position
    }

    override fun resetForBenchmark(position: AnalysisPosition) {
        setPosition(position)
    }

    override fun analyze(visits: Int, cancelled: AtomicBoolean): PositionAnalysis? {
        if (cancelled.get()) return null
        val current = checkNotNull(position) { "Cloud engine has no selected position" }
        // The server currently follows the ordinary alternating Go turn sequence.
        // Special SGF PL edits remain fully supported by Local and Hybrid fallback.
        for (index in 1 until current.moveColors.size) {
            require(current.moveColors[index] != current.moveColors[index - 1]) {
                "This record contains a non-alternating PL edit; use Hybrid or Local mode"
            }
        }
        val expectedNext = current.moveColors.lastOrNull()?.let { 3 - it } ?: current.initialPlayer
        require(current.nextPlayer == expectedNext) {
            "This record changes the player to move without a played move; use Hybrid or Local mode"
        }

        val requestId = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("request_id", requestId)
            .put("board_x_size", 19)
            .put("board_y_size", 19)
            .put("rules", current.rules.id)
            .put("komi", current.komi.toDouble())
            .put("to_move", if (current.nextPlayer == 1) "B" else "W")
            .put("moves", JSONArray().apply {
                current.moves.forEachIndexed { index, point ->
                    put(JSONArray().put(if (current.moveColors[index] == 1) "B" else "W").put(toGtp(point)))
                }
            })
            .put("initial_stones", JSONArray().apply {
                current.initialBlack.forEach { put(JSONArray().put("B").put(toGtp(it))) }
                current.initialWhite.forEach { put(JSONArray().put("W").put(toGtp(it))) }
            })
            .put("max_visits", visits)

        val response = post(payload, cancelled) ?: return null
        if (cancelled.get()) return null
        check(response.optInt("protocol_version") == 1) { "Unsupported cloud protocol version" }
        check(response.optString("request_id") == requestId) { "Cloud response request ID mismatch" }
        check(response.optString("source") == "cloud") { "Server did not identify this result as cloud analysis" }

        val root = response.optJSONObject("root_info") ?: error("Cloud response is missing root_info")
        val blackWinRate = (root.getDouble("winrate") * 100.0).toFloat()
        val blackLead = root.getDouble("scoreLead").toFloat()
        val scoreMean = root.optDouble("scoreSelfplay", blackLead.toDouble()).toFloat()
        require(blackWinRate.isFinite() && blackWinRate in 0f..100f && blackLead.isFinite() && scoreMean.isFinite()) {
            "Cloud response contains invalid evaluation values"
        }

        val moveInfos = response.optJSONArray("move_infos") ?: JSONArray()
        val candidates = (0 until minOf(3, moveInfos.length())).mapNotNull { index ->
            val info = moveInfos.optJSONObject(index) ?: return@mapNotNull null
            val moveText = info.optString("move")
            val move = fromGtp(moveText)
            val visitsForMove = info.optInt("visits", 0)
            if (visitsForMove <= 0) return@mapNotNull null
            val pvJson = info.optJSONArray("pv") ?: JSONArray()
            val pv = (0 until pvJson.length()).mapNotNull { pvIndex ->
                runCatching { fromGtp(pvJson.getString(pvIndex)) }.getOrNull()
            }.ifEmpty { listOf(move) }
            AnalysisCandidate(
                label = ('A' + index).toString(),
                move = move,
                visits = visitsForMove,
                blackWinRate = (info.getDouble("winrate") * 100.0).toFloat().coerceIn(0f, 100f),
                blackLead = info.getDouble("scoreLead").toFloat(),
                pv = pv.take(8),
            )
        }
        val bestMove = response.optString("best_move").takeIf { it.isNotBlank() && it != "null" }?.let(::fromGtp)
            ?: candidates.firstOrNull()?.move
        val finished = bestMove == null && candidates.isEmpty()
        val totalVisits = if (finished) 0 else root.optInt("visits", response.optInt("completed_visits", 0))
        if (!finished) require(totalVisits > 0) { "Cloud response did not complete any visits" }
        return PositionAnalysis(
            position = current,
            visits = totalVisits,
            blackWinRate = blackWinRate,
            blackLead = blackLead,
            blackScoreMean = scoreMean,
            candidates = candidates,
            backend = backend,
            gameFinished = finished,
            bestMove = if (finished) null else bestMove,
        )
    }

    override fun close() = Unit

    private fun post(payload: JSONObject, cancelled: AtomicBoolean): JSONObject? {
        val activeConnection = AtomicReference<HttpURLConnection?>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "katago-cloud-http").apply { isDaemon = true }
        }
        val future: Future<JSONObject> = executor.submit<JSONObject> {
            val connection = analyzeUrl.openConnection() as? HttpsURLConnection
                ?: error("Cloud endpoint did not create an HTTPS connection")
            activeConnection.set(connection)
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 600_000
                connection.useCaches = false
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val detail = runCatching { JSONObject(body).optString("detail") }.getOrDefault(body)
                    error("Cloud server returned HTTP $code: \${detail.take(240)}")
                }
                JSONObject(body)
            } finally {
                connection.disconnect()
            }
        }
        try {
            while (true) {
                if (cancelled.get()) {
                    activeConnection.get()?.disconnect()
                    future.cancel(true)
                    throw CancellationException("Cloud analysis cancelled")
                }
                try {
                    return future.get(100, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // Poll the controller's cancellation token while the HTTP worker waits.
                } catch (error: ExecutionException) {
                    val cause = error.cause
                    if (cause is Exception) throw cause
                    throw IllegalStateException("Cloud analysis failed", cause)
                }
            }
        } finally {
            activeConnection.get()?.disconnect()
            future.cancel(true)
            executor.shutdownNow()
        }
    }

    private fun toGtp(point: Int): String {
        if (point == PASS) return "pass"
        require(point in 0 until PASS)
        val columns = "ABCDEFGHJKLMNOPQRST"
        return "\${columns[point % 19]}\${19 - point / 19}"
    }

    private fun fromGtp(move: String): Int {
        if (move.equals("pass", true)) return PASS
        require(move.length in 2..3) { "Invalid cloud move: $move" }
        val columns = "ABCDEFGHJKLMNOPQRST"
        val column = columns.indexOf(move[0].uppercaseChar())
        val row = move.substring(1).toIntOrNull() ?: -1
        require(column in 0..18 && row in 1..19) { "Invalid cloud move: $move" }
        return (19 - row) * 19 + column
    }
}

/** Cloud-first wrapper that transparently falls back to the validated local engine. */
class HybridAnalysisSession(
    private val cloud: CloudAnalysisSession,
    private val localFactory: () -> AnalysisSession,
) : AnalysisSession {
    private var cloudActive = true
    private var local: AnalysisSession? = null
    private var currentPosition: AnalysisPosition? = null
    override val backend: String get() = if (cloudActive) "CLOUD" else checkNotNull(local).backend
    override val supportsTimedAnalysis: Boolean = false

    private fun localSession(): AnalysisSession = local ?: localFactory().also {
        currentPosition?.let(it::setPosition)
        local = it
    }

    override fun setPosition(position: AnalysisPosition) {
        currentPosition = position
        cloud.setPosition(position)
        local?.setPosition(position)
    }

    override fun resetForBenchmark(position: AnalysisPosition) {
        setPosition(position)
        local?.resetForBenchmark(position)
    }

    override fun analyze(visits: Int, cancelled: AtomicBoolean): PositionAnalysis? {
        if (cloudActive) {
            try {
                return cloud.analyze(visits, cancelled)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (cancelled.get()) throw CancellationException("Hybrid analysis cancelled")
                cloudActive = false
            }
        }
        return localSession().analyze(visits, cancelled)
    }

    override fun close() {
        cloud.close()
        local?.close()
        local = null
    }
}
