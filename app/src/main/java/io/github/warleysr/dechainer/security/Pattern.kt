package io.github.warleysr.dechainer.security

/**
 * The 3x3 dot pattern behind the opening lock and the private screens. Dots are numbered 0 to 8,
 * row by row. Pure (no Android types), so it is unit tested.
 */
object Pattern {
    const val SIDE = 3
    const val DOTS = SIDE * SIDE
    const val MIN_DOTS = 4

    /** At least four different dots. */
    fun isValid(dots: List<Int>): Boolean =
        dots.size in MIN_DOTS..DOTS && dots.all { it in 0 until DOTS } && dots.toSet().size == dots.size

    /** The text that is hashed: the dot numbers in order, such as "01258". */
    fun encode(dots: List<Int>): String = dots.joinToString("")

    /**
     * The dot a straight line from [a] to [b] passes over, if any (0 to 2, 1 to 1, 0 to 8 and the
     * like), so a pattern cannot skip over a dot without taking it.
     */
    fun between(a: Int, b: Int): Int? {
        if (a == b) return null
        val dr = Math.abs(a / SIDE - b / SIDE)
        val dc = Math.abs(a % SIDE - b % SIDE)
        if (dr % 2 != 0 || dc % 2 != 0) return null
        return ((a / SIDE + b / SIDE) / 2) * SIDE + (a % SIDE + b % SIDE) / 2
    }

    /**
     * The dot under a touch at ([x], [y]) in a square pad [side] wide, or null. A touch counts when
     * it is within [reach] of a cell's width from the dot's centre.
     */
    fun dotAt(x: Float, y: Float, side: Float, reach: Float = 0.32f): Int? {
        if (side <= 0f || x < 0f || y < 0f || x >= side || y >= side) return null
        val cell = side / SIDE
        val col = (x / cell).toInt().coerceIn(0, SIDE - 1)
        val row = (y / cell).toInt().coerceIn(0, SIDE - 1)
        val dx = x - (col + 0.5f) * cell
        val dy = y - (row + 0.5f) * cell
        return if (dx * dx + dy * dy <= (cell * reach) * (cell * reach)) row * SIDE + col else null
    }
}
