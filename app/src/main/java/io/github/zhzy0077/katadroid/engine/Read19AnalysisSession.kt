package io.github.zhzy0077.katadroid.engine

import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read19 cloud adapter.
 *
 * Read19 documents a KataGo Analysis Engine JSON WebSocket at
 * wss://read19.com/v1/katago/{API_KEY}. The credential is part of the URL
 * path as required by Read19 and must never be logged or shown in diagnostics.
 */
class Read19AnalysisSession(
    endpoint: String,
    private val token: String,
) : AnalysisSession {
    private val websocketUrl: String = toWebSocketUrl(endpoint, token)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private var position: AnalysisPosition? = null
    override val backend: String = "CLOUD"
    override val supportsTimedAnalysis: Boolean = false

    init {
        require(token.startsWith("kg_live_")) { "Read19 analysis key must start with kg_live_" }
    }

    override fun setPosition(position: AnalysisPosition) {
        this.position = position
    }

    override fun resetForBenchmark(position: AnalysisPosition) {
        setPosition(position)
    }

    override fun analyze(visits: Int, cancelled: AtomicBoolean): PositionAnalysis? {
        if (cancelled.get()) return null
        val current = checkNotNull(position) { "Read19 has no selected position" }
        require(visits in 1..50_000) { "Visit budget is outside the supported app range" }

        // Read19's documented WSS payload is the standard KataGo Analysis Engine JSON.
        val requestId = UUID.randomUUID().toString()
        val query = JSONObject()
            .put("id", requestId)
            .put("moves", JSONArray().apply {
                current.moves.forEachIndexed { index, point ->
                    put(JSONArray().put(if (current.moveColors[index] == 1) "B" else "W").put(toGtp(point)))
                }
            })
            .put("initialStones", JSONArray().apply {
                current.initialBlack.forEach { put(JSONArray().put("B").put(toGtp(it))) }
                current.initialWhite.forEach { put(JSONArray().put("W").put(toGtp(it))) }
            })
            .put("initialPlayer", if (current.initialPlayer == 1) "B" else "W")
            .put("rules", current.rules.id)
            .put("komi", current.komi.toDouble())
            .put("boardXSize", 19)
            .put("boardYSize", 19)
            .put("analyzeTurns", JSONArray().put(current.moves.size))
            .put("maxVisits", visits)
            .put("reportDuringSearchEvery", 1.0)
            .put("overrideSettings", JSONObject().put("reportAnalysisWinratesAs", "BLACK"))

        val opened = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val socketRef = AtomicReference<WebSocket?>()
        val failure = AtomicReference<Throwable?>()
        val finalResponse = AtomicReference<JSONObject?>()

        val request = Request.Builder()
            .url(websocketUrl)
            .header("Origin", "https://read19.com")
            .build()

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketRef.set(webSocket)
                opened.countDown()
                if (!webSocket.send(query.toString())) {
                    failure.compareAndSet(null, IllegalStateException("Read19 rejected the analysis request"))
                    completed.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    if (message.optString("id") != requestId) return
                    val errorText = message.optString("error")
                    if (errorText.isNotBlank() && errorText != "null") {
                        failure.compareAndSet(null, IllegalStateException("Read19: $errorText"))
                        completed.countDown()
                        return
                    }
                    // Ignore standalone KataGo warnings; only a final rootInfo response completes the query.
                    if (message.has("rootInfo") && !message.optBoolean("isDuringSearch", false)) {
                        finalResponse.set(message)
                        completed.countDown()
                    }
                } catch (error: Exception) {
                    failure.compareAndSet(null, IllegalStateException("Read19 returned invalid KataGo JSON", error))
                    completed.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Do not propagate OkHttp's request exception: it may contain the credential URL.
                val status = response?.code?.let { "HTTP $it" } ?: "network error"
                failure.compareAndSet(null, IllegalStateException("Read19 WebSocket connection failed ($status)"))
                opened.countDown()
                completed.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (finalResponse.get() == null && failure.get() == null) {
                    failure.compareAndSet(null, IllegalStateException("Read19 closed the connection before analysis completed ($code)"))
                }
                completed.countDown()
            }
        })
        socketRef.compareAndSet(null, socket)

        try {
            // Poll the connection latch so changing position can cancel promptly.
            val connectDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
            while (opened.count > 0) {
                if (cancelled.get()) throw CancellationException("Read19 analysis cancelled")
                if (System.nanoTime() >= connectDeadline) {
                    throw IllegalStateException("Timed out connecting to Read19")
                }
                opened.await(150, TimeUnit.MILLISECONDS)
            }
            failure.get()?.let { throw it }

            // A broken or stalled remote job must not leave the analysis worker blocked forever.
            val analysisDeadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
            while (!completed.await(150, TimeUnit.MILLISECONDS)) {
                if (cancelled.get()) throw CancellationException("Read19 analysis cancelled")
                failure.get()?.let { throw it }
                if (System.nanoTime() >= analysisDeadline) {
                    throw IllegalStateException("Read19 analysis timed out after 5 minutes")
                }
            }
            if (cancelled.get()) throw CancellationException("Read19 analysis cancelled")
            failure.get()?.let { throw it }
            val response = checkNotNull(finalResponse.get()) { "Read19 ended without a final analysis response" }
            return parseResponse(current, response, requestId)
        } finally {
            // Read19 supports cancelling one query without ending the WSS protocol first.
            if (cancelled.get()) {
                socketRef.get()?.send(JSONObject()
                    .put("action", "terminate")
                    .put("terminateId", requestId)
                    .toString())
            }
            socketRef.get()?.close(1000, if (cancelled.get()) "analysis cancelled" else "analysis complete")
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun parseResponse(current: AnalysisPosition, response: JSONObject, requestId: String): PositionAnalysis {
        check(response.optString("id") == requestId) { "Read19 response ID mismatch" }
        val root = response.optJSONObject("rootInfo") ?: error("Read19 response is missing rootInfo")
        val reportedPlayer = when (root.optString("currentPlayer", if (current.nextPlayer == 1) "B" else "W")) {
            "B" -> 1
            "W" -> 2
            else -> error("Read19 returned an unknown currentPlayer")
        }
        check(reportedPlayer == current.nextPlayer) { "Read19 analyzed the wrong player to move" }
        // The query explicitly requests BLACK perspective so the UI remains consistent with local analysis.
        val blackWinRate = root.getDouble("winrate").toFloat() * 100f
        val blackLead = root.getDouble("scoreLead").toFloat()
        val scoreMean = root.optDouble("scoreSelfplay", blackLead.toDouble()).toFloat()
        require(blackWinRate.isFinite() && blackWinRate in 0f..100f && blackLead.isFinite() && scoreMean.isFinite()) {
            "Read19 returned invalid evaluation values"
        }

        val moveInfos = response.optJSONArray("moveInfos") ?: JSONArray()
        val candidates = (0 until minOf(3, moveInfos.length())).mapNotNull { index ->
            val info = moveInfos.optJSONObject(index) ?: return@mapNotNull null
            val move = fromGtp(info.optString("move"))
            val moveVisits = info.optInt("visits", 0)
            if (moveVisits <= 0) return@mapNotNull null
            val pvJson = info.optJSONArray("pv") ?: JSONArray()
            val pv = (0 until pvJson.length()).mapNotNull { pvIndex ->
                runCatching { fromGtp(pvJson.getString(pvIndex)) }.getOrNull()
            }.ifEmpty { listOf(move) }
            val candidateWinRate = info.getDouble("winrate").toFloat() * 100f
            val candidateLead = info.getDouble("scoreLead").toFloat()
            require(candidateWinRate.isFinite() && candidateLead.isFinite()) { "Read19 returned invalid candidate evaluation" }
            AnalysisCandidate(
                label = ('A' + index).toString(),
                move = move,
                visits = moveVisits,
                blackWinRate = candidateWinRate,
                blackLead = candidateLead,
                pv = pv.take(8),
            )
        }

        val bestMove = candidates.firstOrNull()?.move
        val finished = bestMove == null && candidates.isEmpty()
        val totalVisits = if (finished) 0 else root.optInt("visits", 0)
        if (!finished) require(totalVisits > 0) { "Read19 final response has no completed visits" }
        return PositionAnalysis(
            position = current,
            visits = totalVisits,
            blackWinRate = blackWinRate,
            blackLead = blackLead,
            blackScoreMean = scoreMean,
            candidates = candidates,
            backend = backend,
            gameFinished = finished,
            bestMove = bestMove,
        )
    }

    private fun toGtp(point: Int): String {
        if (point == PASS) return "pass"
        require(point in 0 until PASS)
        val columns = "ABCDEFGHJKLMNOPQRST"
        return "${columns[point % 19]}${19 - point / 19}"
    }

    private fun fromGtp(move: String): Int {
        if (move.equals("pass", true)) return PASS
        require(move.length in 2..3) { "Invalid Read19 move: $move" }
        val columns = "ABCDEFGHJKLMNOPQRST"
        val column = columns.indexOf(move[0].uppercaseChar())
        val row = move.substring(1).toIntOrNull() ?: -1
        require(column in 0..18 && row in 1..19) { "Invalid Read19 move: $move" }
        return (19 - row) * 19 + column
    }

    companion object {
        private fun toWebSocketUrl(endpoint: String, token: String): String {
            val base = endpoint.trim().trimEnd('/')
            require(base.startsWith("https://", true)) { "Read19 endpoint must use HTTPS" }
            val parsed = java.net.URL(base)
            require(parsed.host.equals("read19.com", true) || parsed.host.endsWith(".read19.com", true)) {
                "Read19 endpoint must be a Read19 HTTPS address"
            }
            require(parsed.userInfo == null && parsed.query == null && parsed.ref == null) { "Invalid Read19 endpoint" }
            return "wss://${parsed.host}/v1/katago/${java.net.URLEncoder.encode(token, Charsets.UTF_8.name())}"
        }
    }
}
