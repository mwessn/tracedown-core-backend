package dev.tracedown.common.util

/**
 * Minimal unified line diff (LCS-based) for audit trails. Scripts are small,
 * so the O(n·m) table is fine; output is capped so a pathological paste
 * can't bloat the audit row.
 */
object LineDiff {

    private const val MAX_OUTPUT_CHARS = 8_000

    /** Unified-style diff of two texts with [context] lines around changes. */
    fun unified(old: String, new: String, context: Int = 2): String {
        val a = old.lines()
        val b = new.lines()

        // LCS table
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }

        // Backtrack into an op stream: ' ' keep, '-' removed, '+' added
        data class Op(val tag: Char, val line: String, val oldNo: Int, val newNo: Int)
        val ops = mutableListOf<Op>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { ops.add(Op(' ', a[i], i + 1, j + 1)); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { ops.add(Op('-', a[i], i + 1, j + 1)); i++ }
                else -> { ops.add(Op('+', b[j], i + 1, j + 1)); j++ }
            }
        }
        while (i < a.size) { ops.add(Op('-', a[i], i + 1, j + 1)); i++ }
        while (j < b.size) { ops.add(Op('+', b[j], i + 1, j + 1)); j++ }

        if (ops.none { it.tag != ' ' }) return ""

        // Keep only changed ops plus `context` unchanged lines around them.
        val keep = BooleanArray(ops.size)
        for (k in ops.indices) {
            if (ops[k].tag != ' ') {
                for (c in maxOf(0, k - context)..minOf(ops.size - 1, k + context)) keep[c] = true
            }
        }

        val sb = StringBuilder()
        var k = 0
        while (k < ops.size) {
            if (!keep[k]) { k++; continue }
            // Hunk start
            val startOld = ops[k].oldNo
            val startNew = ops[k].newNo
            sb.append("@@ -").append(startOld).append(" +").append(startNew).append(" @@\n")
            while (k < ops.size && keep[k]) {
                sb.append(ops[k].tag).append(ops[k].line).append('\n')
                if (sb.length > MAX_OUTPUT_CHARS) {
                    sb.append("… (diff truncated)\n")
                    return sb.toString()
                }
                k++
            }
        }
        return sb.toString().trimEnd('\n')
    }
}
