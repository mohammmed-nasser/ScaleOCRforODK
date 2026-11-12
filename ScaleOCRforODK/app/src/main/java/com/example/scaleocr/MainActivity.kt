
private data class DigitBox(
    val left: Int,
    val right: Int,
    val top: Int,
    val bottom: Int
)

private fun recognizeSevenSegment(bitmap: Bitmap): String? {

    val width = bitmap.width
    val height = bitmap.height

    if (width < 20 || height < 20) return null

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    fun isDark(x: Int, y: Int): Boolean {
        if (x !in 0 until width || y !in 0 until height) return false
        return (pixels[y * width + x] and 0xFF) < 128
    }

    // حساب عدد البكسلات الداكنة في كل عمود.
    val columnCounts = IntArray(width)

    for (x in 0 until width) {
        for (y in 0 until height) {
            if (isDark(x, y)) columnCounts[x]++
        }
    }

    val columnThreshold = max(2, (height * 0.06f).toInt())

    // تحديد الأعمدة التي تحتوي على أجزاء من الأرقام.
    val active = BooleanArray(width) { x ->
        columnCounts[x] >= columnThreshold
    }

    // فصل الأرقام اعتماداً على المسافات بينها.
    val boxes = mutableListOf<DigitBox>()
    var x = 0

    while (x < width) {

        if (!active[x]) {
            x++
            continue
        }

        val start = x

        while (x < width && active[x]) {
            x++
        }

        val end = x - 1

        val digitWidth = end - start + 1

        if (digitWidth < height * 0.12f ||
            digitWidth > height * 0.90f
        ) continue

        var top = height
        var bottom = -1

        for (yy in 0 until height) {

            var count = 0

            for (xx in start..end) {
                if (isDark(xx, yy)) count++
            }

            if (count >= max(2, (digitWidth * 0.08f).toInt())) {
                top = min(top, yy)
                bottom = max(bottom, yy)
            }
        }

        if (bottom > top) {
            boxes.add(DigitBox(start, end, top, bottom))
        }
    }

    if (boxes.size < 2) return null

    // أنماط الأرقام بترتيب المقاطع:
    // أعلى، يمين أعلى، يمين أسفل، أسفل،
    // يسار أسفل، يسار أعلى، وسط.
    val patterns = mapOf(
        63 to '0',
        6 to '1',
        91 to '2',
        79 to '3',
        102 to '4',
        109 to '5',
        125 to '6',
        7 to '7',
        127 to '8',
        111 to '9'
    )

    fun segmentIsOn(
        box: DigitBox,
        centerX: Float,
        centerY: Float,
        patchWidth: Float,
        patchHeight: Float
    ): Boolean {

        val x1 = max(
            box.left,
            (box.left + (box.right - box.left) * centerX -
                patchWidth / 2).toInt()
        )

        val x2 = min(
            box.right,
            (box.left + (box.right - box.left) * centerX +
                patchWidth / 2).toInt()
        )

        val y1 = max(
            box.top,
            (box.top + (box.bottom - box.top) * centerY -
                patchHeight / 2).toInt()
        )

        val y2 = min(
            box.bottom,
            (box.top + (box.bottom - box.top) * centerY +
                patchHeight / 2).toInt()
        )

        if (x2 <= x1 || y2 <= y1) return false

        var dark = 0
        var total = 0

        for (yy in y1..y2) {
            for (xx in x1..x2) {
                total++
                if (isDark(xx, yy)) dark++
            }
        }

        return total > 0 && dark.toFloat() / total >= 0.30f
    }

    fun classifyDigit(box: DigitBox): Char? {

        val digitWidth = (box.right - box.left).toFloat()
        val digitHeight = (box.bottom - box.top).toFloat()

        if (digitWidth <= 0 || digitHeight <= 0) return null

        val points = arrayOf(
            0.50f to 0.12f, // أعلى
            0.86f to 0.30f, // يمين أعلى
            0.86f to 0.70f, // يمين أسفل
            0.50f to 0.88f, // أسفل
            0.14f to 0.70f, // يسار أسفل
            0.14f to 0.30f, // يسار أعلى
            0.50f to 0.50f  // الوسط
        )

        var mask = 0

        for (i in points.indices) {

            val point = points[i]

            val on = segmentIsOn(
                box,
                point.first,
                point.second,
                digitWidth * 0.22f,
                digitHeight * 0.20f
            )

            if (on) mask = mask or (1 shl i)
        }

        // اختيار أقرب نمط معروف.
        val best = patterns.minByOrNull {
            Integer.bitCount(it.key xor mask)
        } ?: return null

        // نرفض الرقم إذا كان الاختلاف كبيراً.
        if (Integer.bitCount(best.key xor mask) > 1) {
            return null
        }

        return best.value
    }

    val digits = boxes.map { classifyDigit(it) ?: return null }

    // البحث عن النقطة العشرية في المسافة بين الأرقام.
    fun hasDecimalAfter(index: Int): Boolean {

        val current = boxes[index]
        val next = boxes.getOrNull(index + 1) ?: return false

        val gapStart = current.right + 1
        val gapEnd = next.left - 1

        if (gapEnd < gapStart) return false

        val digitHeight = current.bottom - current.top + 1

        val lowerStart =
            current.top + (digitHeight * 0.72f).toInt()

        var darkCount = 0
        var minX = width
        var maxX = -1
        var minY = height
        var maxY = -1

        for (yy in lowerStart until min(height, current.bottom + 1)) {
            for (xx in gapStart..gapEnd) {

                if (isDark(xx, yy)) {
                    darkCount++
                    minX = min(minX, xx)
                    maxX = max(maxX, xx)
                    minY = min(minY, yy)
                    maxY = max(maxY, yy)
                }
            }
        }

        if (darkCount < 2) return false

        val dotWidth = maxX - minX + 1
        val dotHeight = maxY - minY + 1

        // النقطة صغيرة مقارنة بحجم الرقم.
        return dotWidth <= digitHeight * 0.20f &&
               dotHeight <= digitHeight * 0.22f
    }

    val output = StringBuilder()

    for (i in digits.indices) {
        output.append(digits[i])

        if (hasDecimalAfter(i)) {
            output.append('.')
        }
    }

    val value = output.toString()

    // لا نقبل قراءة لا تحتوي على فاصلة عشرية.
    if (!value.contains('.')) return null

    if (value.count { it == '.' } != 1) return null

    if (value.startsWith('.') || value.endsWith('.')) return null

    return value
}
