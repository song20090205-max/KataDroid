package io.github.zhzy0077.katadroid.sgf

/**
 * A stone detected in a Go-board image. Coordinates are zero-based from the
 * upper-left intersection. A null moveNumber means the stone is a setup stone
 * (for example, a handicap stone) rather than a numbered move.
 */
data class ImageStone(
    val x: Int,
    val y: Int,
    val color: Int,
    val moveNumber: Int? = null,
)

object ImageSgfConverter {
    /**
     * Converts a reviewed image recognition result into a single 19x19 SGF.
     * Unnumbered stones become root setup properties; numbered stones become
     * an ordered main line. No guessed move order is introduced.
     */
    fun convert(
        stones: List<ImageStone>,
        komi: Float = 7.5f,
        rules: String = "Chinese",
    ): String {
        require(stones.isNotEmpty()) { "No stones were detected" }
        require(stones.size <= 361) { "A 19x19 board cannot contain more than 361 stones" }
        require(komi.isFinite() && komi in -400f..400f) { "Invalid komi" }
        require(rules in setOf("Chinese", "Japanese", "Korean")) { "Unsupported rules" }
        stones.forEach {
            require(it.x in 0..18 && it.y in 0..18) { "Stone is outside the 19x19 board" }
            require(it.color == 1 || it.color == 2) { "Stone color must be black (1) or white (2)" }
            require(it.moveNumber == null || it.moveNumber in 1..2000) { "Invalid move number" }
        }
        require(stones.map { it.x to it.y }.distinct().size == stones.size) { "Two stones occupy the same intersection" }
        val numbered = stones.filter { it.moveNumber != null }.sortedBy { it.moveNumber }
        val setup = stones.filter { it.moveNumber == null }
        require(numbered.isNotEmpty()) { "No numbered moves were detected" }
        require(numbered.mapNotNull { it.moveNumber } == (1..numbered.size).toList()) {
            "Move numbers must be continuous from 1; review the detected numbers"
        }
        numbered.forEachIndexed { index, stone ->
            val expected = if (index % 2 == 0) 1 else 2
            require(stone.color == expected) { "Move ${index + 1} has the wrong color for alternating play" }
        }
        require(setup.map { it.color }.distinct().size <= 2)
        val rootProperties = linkedMapOf(
            "FF" to listOf("4"),
            "GM" to listOf("1"),
            "CA" to listOf("UTF-8"),
            "AP" to listOf("KataGo Master:1.0"),
            "SZ" to listOf("19"),
            "RU" to listOf(rules),
            "KM" to listOf(komi.toString()),
        )
        setup.filter { it.color == 1 }.takeIf { it.isNotEmpty() }?.let {
            rootProperties["AB"] = it.map { stone -> pointSgf(stone.y * 19 + stone.x) }
        }
        setup.filter { it.color == 2 }.takeIf { it.isNotEmpty() }?.let {
            rootProperties["AW"] = it.map { stone -> pointSgf(stone.y * 19 + stone.x) }
        }
        val root = SgfNode(rootProperties)
        var parent = root
        numbered.forEach { stone ->
            val property = if (stone.color == 1) "B" else "W"
            val child = SgfNode(mapOf(property to listOf(pointSgf(stone.y * 19 + stone.x))))
            parent.children += child
            parent = child
        }
        return SgfCodec.encode(root)
    }
}
