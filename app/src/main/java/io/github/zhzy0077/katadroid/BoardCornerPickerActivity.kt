package io.github.zhzy0077.katadroid

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Typeface
import kotlin.math.roundToInt

/**
 * User marks the four outer grid intersections in clockwise order.
 * Recognition is intentionally reviewable: no move is submitted without the
 * user's explicit confirmation and choice of side to play.
 */
class BoardCornerPickerActivity : Activity() {
    private lateinit var picker: BoardCornerPickerView
    private lateinit var root: LinearLayout
    private var boardReview: BoardReviewView? = null
    private lateinit var status: TextView
    private lateinit var recognizeButton: Button
    private lateinit var resetButton: Button
    private var recognized: RecognizedBoard? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_IMAGE_PATH) ?: return finish()
        val bitmap = BitmapFactory.decodeFile(path) ?: return finish()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setBackgroundColor(Color.rgb(18, 22, 28))
        }
        status = TextView(this).apply {
            text = getString(R.string.board_capture_corner_hint)
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        picker = BoardCornerPickerView(this, bitmap) { count ->
            status.text = getString(R.string.board_capture_corner_progress, count)
            recognizeButton.isEnabled = count == 4
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        resetButton = Button(this).apply {
            text = getString(R.string.board_capture_reset)
            setOnClickListener {
                boardReview?.let { root.removeView(it) }
                boardReview = null
                picker.visibility = View.VISIBLE
                picker.resetPoints()
                recognized = null
                status.typeface = Typeface.DEFAULT
                status.text = getString(R.string.board_capture_corner_hint)
                recognizeButton.text = getString(R.string.board_capture_recognize)
                recognizeButton.setOnClickListener { recognizeBoard() }
                recognizeButton.isEnabled = false
            }
        }
        recognizeButton = Button(this).apply {
            text = getString(R.string.board_capture_recognize)
            isEnabled = false
            setOnClickListener { recognizeBoard() }
        }
        buttons.addView(resetButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        buttons.addView(recognizeButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(status)
        root.addView(picker, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(buttons)
        setContentView(root)
    }

    private fun recognizeBoard() {
        val corners = picker.imagePoints()
        if (corners.size != 4) return
        val result = runCatching { GoBoardImageRecognizer.recognize(picker.sourceBitmap, corners) }
            .getOrElse {
                status.text = getString(R.string.board_capture_failed)
                return
            }
        recognized = result
        picker.visibility = View.GONE
        status.typeface = Typeface.DEFAULT
        updateReviewStatus(result)
        boardReview?.let { root.removeView(it) }
        boardReview = BoardReviewView(this, result) { corrected ->
            recognized = corrected
            updateReviewStatus(corrected)
        }
        root.addView(boardReview, 1, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        recognizeButton.text = getString(R.string.board_capture_analyze)
        recognizeButton.isEnabled = true
        recognizeButton.setOnClickListener { chooseNextPlayer() }
    }

    private fun updateReviewStatus(board: RecognizedBoard) {
        status.text = getString(
            R.string.board_capture_result,
            board.blackCount, board.whiteCount, board.uncertainCount
        ) + "\n點擊棋盤交叉點可循環切換：空位 → 黑棋 → 白棋。"
    }

    private fun chooseNextPlayer() {
        val board = recognized ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.board_capture_next_player)
            .setItems(arrayOf(getString(R.string.black_to_play), getString(R.string.white_to_play))) { _, which ->
                val player = if (which == 0) "B" else "W"
                val sgf = board.toSgf(player)
                startActivity(Intent(this, MainActivity::class.java).apply {
                    putExtra(EXTRA_IMPORTED_SGF, sgf)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                })
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val EXTRA_IMAGE_PATH = "board_picker_image_path"
        const val EXTRA_IMPORTED_SGF = "screen_recognized_sgf"
    }
}

private class BoardCornerPickerView(
    context: android.content.Context,
    val sourceBitmap: Bitmap,
    private val onPointsChanged: (Int) -> Unit
) : View(context) {
    private val points = mutableListOf<PointF>()
    private val imageRect = android.graphics.RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.DEFAULT_BOLD
    }

    fun imagePoints(): List<PointF> = points.map { PointF(it.x, it.y) }

    fun resetPoints() {
        points.clear()
        onPointsChanged(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width.toFloat() / sourceBitmap.width, height.toFloat() / sourceBitmap.height)
        val drawnWidth = sourceBitmap.width * scale
        val drawnHeight = sourceBitmap.height * scale
        imageRect.set((width - drawnWidth) / 2f, (height - drawnHeight) / 2f,
            (width + drawnWidth) / 2f, (height + drawnHeight) / 2f)
        canvas.drawColor(Color.rgb(18, 22, 28))
        canvas.drawBitmap(sourceBitmap, null, imageRect, bitmapPaint)
        points.forEachIndexed { index, point ->
            val x = imageRect.left + point.x * imageRect.width() / sourceBitmap.width
            val y = imageRect.top + point.y * imageRect.height() / sourceBitmap.height
            canvas.drawCircle(x, y, 14f, markerPaint)
            canvas.drawText((index + 1).toString(), x + 16f, y - 14f, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP || points.size >= 4) return true
        val scale = minOf(width.toFloat() / sourceBitmap.width, height.toFloat() / sourceBitmap.height)
        val drawnWidth = sourceBitmap.width * scale
        val drawnHeight = sourceBitmap.height * scale
        val left = (width - drawnWidth) / 2f
        val top = (height - drawnHeight) / 2f
        val x = (event.x - left) / scale
        val y = (event.y - top) / scale
        if (x !in 0f..sourceBitmap.width.toFloat() || y !in 0f..sourceBitmap.height.toFloat()) return true
        points += PointF(x, y)
        onPointsChanged(points.size)
        invalidate()
        return true
    }
}

private class BoardReviewView(
    context: android.content.Context,
    initialBoard: RecognizedBoard,
    private val onBoardChanged: (RecognizedBoard) -> Unit
) : View(context) {
    private var board = initialBoard
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(65, 43, 25)
        strokeWidth = 1.5f
    }
    private val woodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(213, 174, 112)
        style = Paint.Style.FILL
    }
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(18, 18, 18)
        style = Paint.Style.FILL
    }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private var boardRect = android.graphics.RectF()
    private var spacing = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(25, 29, 35))
        val side = minOf(width, height).toFloat() * 0.94f
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        boardRect.set(left, top, left + side, top + side)
        canvas.drawRect(boardRect, woodPaint)
        val inset = side * 0.045f
        val startX = left + inset
        val startY = top + inset
        spacing = (side - 2f * inset) / 18f
        for (i in 0 until 19) {
            val x = startX + i * spacing
            val y = startY + i * spacing
            canvas.drawLine(x, startY, x, startY + 18f * spacing, gridPaint)
            canvas.drawLine(startX, y, startX + 18f * spacing, y, gridPaint)
        }
        val star = intArrayOf(3, 9, 15)
        star.forEach { row -> star.forEach { col ->
            canvas.drawCircle(startX + col * spacing, startY + row * spacing,
                spacing * 0.09f, gridPaint)
        } }
        for (row in 0 until 19) for (col in 0 until 19) {
            val x = startX + col * spacing
            val y = startY + row * spacing
            val radius = spacing * 0.43f
            when (board.rows[row][col]) {
                'X' -> {
                    canvas.drawCircle(x, y, radius, blackPaint)
                    canvas.drawCircle(x, y, radius, outlinePaint)
                }
                'O' -> {
                    canvas.drawCircle(x, y, radius, whitePaint)
                    canvas.drawCircle(x, y, radius, outlinePaint)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP || spacing <= 0f) return true
        val inset = boardRect.width() * 0.045f
        val startX = boardRect.left + inset
        val startY = boardRect.top + inset
        val col = ((event.x - startX) / spacing).roundToInt()
        val row = ((event.y - startY) / spacing).roundToInt()
        if (row !in 0..18 || col !in 0..18) return true
        val x = startX + col * spacing
        val y = startY + row * spacing
        if (kotlin.math.abs(event.x - x) > spacing * 0.48f ||
            kotlin.math.abs(event.y - y) > spacing * 0.48f) return true
        val nextRows = board.rows.map { it.toMutableList() }.toMutableList()
        nextRows[row][col] = when (nextRows[row][col]) {
            '.' -> 'X'
            'X' -> 'O'
            'O' -> '.'
            else -> '.'
        }
        board = RecognizedBoard(nextRows)
        onBoardChanged(board)
        invalidate()
        return true
    }
}

private data class RecognizedBoard(val rows: List<List<Char>>) {
    val blackCount get() = rows.sumOf { row -> row.count { it == 'X' } }
    val whiteCount get() = rows.sumOf { row -> row.count { it == 'O' } }
    val uncertainCount get() = rows.sumOf { row -> row.count { it == '?' } }

    fun toSgf(nextPlayer: String): String {
        fun coordinates(mark: Char): String = buildString {
            rows.forEachIndexed { row, values ->
                values.forEachIndexed { column, value ->
                    if (value == mark) append("[").append('a' + column).append('a' + row).append("]")
                }
            }
        }
        val black = coordinates('X')
        val white = coordinates('O')
        return "(;GM[1]FF[4]CA[UTF-8]SZ[19]RU[chinese]KM[7.5]PL[$nextPlayer]" +
            (if (black.isNotEmpty()) "AB$black" else "") +
            (if (white.isNotEmpty()) "AW$white" else "") + ")"
    }
}

private object GoBoardImageRecognizer {
    private const val OUTPUT_SIZE = 760
    private const val GRID_MARGIN = 30f
    private const val GRID_STEP = (OUTPUT_SIZE - 2f * GRID_MARGIN) / 18f

    fun recognize(bitmap: Bitmap, corners: List<PointF>): RecognizedBoard {
        require(corners.size == 4)
        val source = FloatArray(8)
        corners.forEachIndexed { index, point ->
            source[index * 2] = point.x
            source[index * 2 + 1] = point.y
        }
        // The selected points are the outermost grid intersections, not the
        // wooden board's outside edge. Map them to the first/last grid centers.
        val edge = OUTPUT_SIZE - GRID_MARGIN
        val destination = floatArrayOf(GRID_MARGIN, GRID_MARGIN, edge, GRID_MARGIN,
            edge, edge, GRID_MARGIN, edge)
        val matrix = Matrix()
        require(matrix.setPolyToPoly(source, 0, destination, 0, 4))
        val warped = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(warped).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        val radius = 4
        val rows = List(19) { row ->
            List(19) { column ->
                val cx = (GRID_MARGIN + column * GRID_STEP).roundToInt()
                val cy = (GRID_MARGIN + row * GRID_STEP).roundToInt()
                val values = ArrayList<Int>(81)
                for (dy in -radius..radius) for (dx in -radius..radius) {
                    val x = (cx + dx).coerceIn(0, OUTPUT_SIZE - 1)
                    val y = (cy + dy).coerceIn(0, OUTPUT_SIZE - 1)
                    val color = warped.getPixel(x, y)
                    val red = Color.red(color)
                    val green = Color.green(color)
                    val blue = Color.blue(color)
                    values += (0.299 * red + 0.587 * green + 0.114 * blue).roundToInt()
                }
                values.sort()
                val median = values[values.size / 2]
                when {
                    median < 82 -> 'X'
                    median > 218 -> 'O'
                    else -> '.'
                }
            }
        }
        warped.recycle()
        return RecognizedBoard(rows)
    }
}
