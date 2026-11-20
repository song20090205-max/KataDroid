package io.github.zhzy0077.katadroid

import io.github.zhzy0077.katadroid.engine.AnalysisPosition
import io.github.zhzy0077.katadroid.engine.PositionAnalysis
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisReplyTest {
    @Test fun parsesOpponentReplyThreatMetrics() {
        val position = AnalysisPosition(listOf(60))
        val reply = JSONObject()
            .put("move", 101)
            .put("visits", 37)
            .put("opponentWinRate", 56.25)
            .put("winRateSwing", 6.25)
            .put("opponentLead", 3.5)
        val candidate = JSONObject()
            .put("move", 100)
            .put("visits", 120)
            .put("blackWinRate", 48.0)
            .put("blackLead", -1.0)
            .put("pv", JSONArray(listOf(100, 101)))
            .put("opponentReplies", JSONArray().put(reply))
        val result = JSONObject()
            .put("visits", 120)
            .put("blackWinRate", 48.0)
            .put("blackLead", -1.0)
            .put("blackScoreMean", -1.2)
            .put("candidates", JSONArray().put(candidate))

        val parsed = PositionAnalysis.parse(position, result, "CPU")
        val parsedReply = parsed.candidates.single().opponentReplies.single()
        assertEquals(101, parsedReply.move)
        assertEquals(37, parsedReply.visits)
        assertEquals(56.25f, parsedReply.opponentWinRate, 0.001f)
        assertEquals(6.25f, parsedReply.winRateSwing, 0.001f)
        assertEquals(3.5f, parsedReply.opponentLead, 0.001f)
        assertTrue(parsedReply.riskLevel() == io.github.zhzy0077.katadroid.engine.ReplyRisk.WARNING)
        val lowSample = parsedReply.copy(visits = 8)
        assertTrue(lowSample.riskLevel() == io.github.zhzy0077.katadroid.engine.ReplyRisk.LOW_CONFIDENCE)
        val smallSwing = parsedReply.copy(winRateSwing = 2.0f)
        assertTrue(smallSwing.riskLevel() == io.github.zhzy0077.katadroid.engine.ReplyRisk.NONE)
        assertTrue(parsed.candidates.single().opponentReplies.isNotEmpty())
    }

    @Test fun oldCachedAnalysisWithoutReplyMetricsStillLoads() {
        val position = AnalysisPosition(emptyList())
        val candidate = JSONObject()
            .put("move", 100)
            .put("visits", 20)
            .put("blackWinRate", 51.0)
            .put("blackLead", 0.5)
            .put("pv", JSONArray(listOf(100, 101)))
        val result = JSONObject()
            .put("visits", 20)
            .put("blackWinRate", 51.0)
            .put("blackLead", 0.5)
            .put("blackScoreMean", 0.4)
            .put("candidates", JSONArray().put(candidate))

        val parsed = PositionAnalysis.parse(position, result, "CPU")
        assertTrue(parsed.candidates.single().opponentReplies.isEmpty())
    }
}
