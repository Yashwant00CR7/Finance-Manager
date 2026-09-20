package com.yk.finance.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The keypad's arithmetic, in exact money.
 *
 * Every value is a BigDecimal and the result is converted to Long paise - Double never
 * appears, for the same reason it never appears in the importer: 0.1 + 0.2 is not 0.3,
 * and a ledger that is a paisa out is a ledger you stop trusting.
 *
 * Division is the one operation that cannot always land on a paisa, so the result
 * reports whether rounding was needed and the screen says so before you save rather
 * than quietly booking a figure you never typed.
 */
object Calculator {

    data class Result(
        val value: BigDecimal,
        /** True when the exact answer had to be rounded to the nearest paisa. */
        val rounded: Boolean,
    ) {
        val paise: Long get() = value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
    }

    private val CONTEXT = MathContext(24, RoundingMode.HALF_UP)

    /**
     * Evaluates an expression of numbers and + - * / with the usual precedence.
     *
     * Returns null for anything malformed or a division by zero rather than guessing -
     * a keypad that silently evaluates "12..5" to something is worse than one that
     * refuses to save.
     */
    fun evaluate(expression: String): Result? {
        val tokens = tokenise(expression) ?: return null
        if (tokens.isEmpty()) return null
        val rpn = toRpn(tokens) ?: return null
        val exact = evalRpn(rpn) ?: return null
        val snapped = exact.setScale(2, RoundingMode.HALF_UP)
        return Result(
            value = snapped,
            rounded = snapped.compareTo(exact.stripTrailingZeros()) != 0,
        )
    }

    private sealed interface Token {
        data class Num(val value: BigDecimal) : Token
        data class Op(val symbol: Char) : Token
    }

    private fun tokenise(expression: String): List<Token>? {
        val cleaned = expression.replace(",", "").replace("₹", "").trim()
        if (cleaned.isEmpty()) return emptyList()

        val tokens = mutableListOf<Token>()
        val number = StringBuilder()

        fun flush(): Boolean {
            if (number.isEmpty()) return true
            val text = number.toString()
            if (text.count { it == '.' } > 1) return false
            val parsed = text.toBigDecimalOrNull() ?: return false
            tokens += Token.Num(parsed)
            number.clear()
            return true
        }

        cleaned.forEachIndexed { index, c ->
            when {
                c.isDigit() || c == '.' -> number.append(c)
                c in "+-*/×÷" -> {
                    val symbol = when (c) {
                        '×' -> '*'
                        '÷' -> '/'
                        else -> c
                    }
                    // A leading minus is a sign, not an operator: "-50" is negative
                    // fifty, and rejecting it would make the keypad's - key dead first.
                    if (number.isEmpty() && tokens.isEmpty() && symbol == '-') {
                        number.append('-')
                        return@forEachIndexed
                    }
                    if (!flush()) return null
                    if (tokens.isEmpty() || tokens.last() is Token.Op) return null
                    tokens += Token.Op(symbol)
                }
                c.isWhitespace() -> Unit
                else -> return null
            }
            if (index == cleaned.lastIndex && number.isNotEmpty() && !flush()) return null
        }
        if (number.isNotEmpty() && !flush()) return null
        if (tokens.isNotEmpty() && tokens.last() is Token.Op) return null
        return tokens
    }

    private fun precedence(symbol: Char): Int = if (symbol == '*' || symbol == '/') 2 else 1

    private fun toRpn(tokens: List<Token>): List<Token>? {
        val output = mutableListOf<Token>()
        val operators = ArrayDeque<Token.Op>()
        tokens.forEach { token ->
            when (token) {
                is Token.Num -> output += token
                is Token.Op -> {
                    while (
                        operators.isNotEmpty() &&
                        precedence(operators.last().symbol) >= precedence(token.symbol)
                    ) {
                        output += operators.removeLast()
                    }
                    operators.addLast(token)
                }
            }
        }
        while (operators.isNotEmpty()) output += operators.removeLast()
        return output
    }

    private fun evalRpn(rpn: List<Token>): BigDecimal? {
        val stack = ArrayDeque<BigDecimal>()
        rpn.forEach { token ->
            when (token) {
                is Token.Num -> stack.addLast(token.value)
                is Token.Op -> {
                    val right = stack.removeLastOrNull() ?: return null
                    val left = stack.removeLastOrNull() ?: return null
                    val value = when (token.symbol) {
                        '+' -> left.add(right)
                        '-' -> left.subtract(right)
                        '*' -> left.multiply(right)
                        '/' -> {
                            if (right.compareTo(BigDecimal.ZERO) == 0) return null
                            left.divide(right, CONTEXT)
                        }
                        else -> return null
                    }
                    stack.addLast(value)
                }
            }
        }
        return stack.singleOrNull()
    }
}
