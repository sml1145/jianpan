package com.mengting.ime.core

/**
 * 计算器表达式求值：支持 + - × ÷ % ( ) 与小数，递归下降解析，无 eval。
 */
object CalcEval {
    fun eval(expr: String): Double? {
        val s = expr.replace("×", "*").replace("÷", "/").replace("−", "-").replace(" ", "")
        if (s.isEmpty()) return null
        return try {
            val p = Parser(s)
            val v = p.parseExpr()
            if (p.pos != s.length) null else v
        } catch (e: Exception) { null }
    }

    /** 结果格式化：整数不带小数点 */
    fun format(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "错误"
        return if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString()
        else String.format("%.6f", v).trimEnd('0').trimEnd('.')
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun parseExpr(): Double {
            var v = parseTerm()
            while (pos < s.length) {
                when (s[pos]) {
                    '+' -> { pos++; v += parseTerm() }
                    '-' -> { pos++; v -= parseTerm() }
                    else -> return v
                }
            }
            return v
        }

        private fun parseTerm(): Double {
            var v = parseFactor()
            while (pos < s.length) {
                when (s[pos]) {
                    '*' -> { pos++; v *= parseFactor() }
                    '/' -> { pos++; v /= parseFactor() }
                    '%' -> { pos++; v %= parseFactor() }
                    else -> return v
                }
            }
            return v
        }

        private fun parseFactor(): Double {
            if (pos >= s.length) throw IllegalArgumentException("unexpected end")
            if (s[pos] == '-') { pos++; return -parseFactor() }
            if (s[pos] == '+') { pos++; return parseFactor() }
            if (s[pos] == '(') {
                pos++
                val v = parseExpr()
                if (pos < s.length && s[pos] == ')') pos++
                return v
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("bad token at $pos")
            return s.substring(start, pos).toDouble()
        }
    }
}
