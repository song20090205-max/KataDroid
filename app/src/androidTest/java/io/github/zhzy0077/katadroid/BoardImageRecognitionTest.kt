package io.github.zhzy0077.katadroid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardImageRecognitionTest {
    @Test
    fun recognizesBlackWhiteAndEmptyIntersectionsIndependentOfTapOrder() {
        val bitmap = syntheticBoard()
        val tl = PointF(30f, 30f)
        val tr = PointF(730f, 30f)
        val br = PointF(730f, 730f)
        val bl = PointF(30f, 730f)
        val tapOrders = listOf(
            listOf(tl, tr, br, bl),
            listOf(br, bl, tl, tr),
            listOf(tr, tl, bl, br),
            listOf(bl, br, tr, tl)
        )

        try {
            tapOrders.forEach { taps ->
                val board = GoBoardImageRecognizer.recognize(bitmap, taps)
                assertEquals("black stone must remain at row 3, column 4", 'X', board.rows[3][4])
                assertEquals("white stone must remain at row 10, column 12", 'O', board.rows[10][12])
                assertEquals("empty intersection must not become a stone", '.', board.rows[7][7])
                assertEquals(1, board.blackCount)
                assertEquals(1, board.whiteCount)
                assertEquals(0, board.uncertainCount)
                val sgf = board.toSgf("B")
                assertTrue(sgf.contains("AB[ed]"))
                assertTrue(sgf.contains("AW[mk]"))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun syntheticBoard(): Bitmap {
        val size = 760
        val margin = 30f
        val step = (size - 2f * margin) / 18f
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(213, 174, 112))
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(65, 43, 25)
            strokeWidth = 2f
        }
        for (i in 0 until 19) {
            val p = margin + i * step
            canvas.drawLine(p, margin, p, size - margin, grid)
            canvas.drawLine(margin, p, size - margin, p, grid)
        }
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(18, 18, 18) }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        fun center(row: Int, column: Int) {
            val x = margin + column * step
            val y = margin + row * step
            canvas.drawCircle(x, y, step * 0.40f, if (row == 3) black else white)
        }
        center(3, 4)
        center(10, 12)
        return bitmap
    }
}
