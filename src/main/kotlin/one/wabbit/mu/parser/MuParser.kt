package one.wabbit.mu.parser

import one.wabbit.math.Rational
import one.wabbit.parsing.CharInput
import one.wabbit.parsing.Pos
import one.wabbit.parsing.TextAndPosSpan
import java.io.File
import java.nio.file.Path

private fun Char.isWhitespace(): Boolean =
    this == ' ' || this == '\t' || this == '\r' || this == '\n'

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun Char.isDigit(): Boolean =
    this in '0'..'9'

private const val BRACKET_OPEN   = '['  // used for lists
private const val BRACKET_CLOSE  = ']'  // used for lists
private const val BRACE_OPEN     = '{'  // used for maps
private const val BRACE_CLOSE    = '}'  // used for maps
private const val PAREN_OPEN     = '('  // used for grouping
private const val PAREN_CLOSE    = ')'  // used for grouping
private const val QUOTE_SINGLE   = '\'' // used in strings
private const val QUOTE_DOUBLE   = '"'  // used in strings
private const val QUOTE_BACKTICK = '`'  // used for quoting and unquoting
private const val BACKSLASH      = '\\' // used in escape sequences
private const val COMMA          = ','  // used in maps
private const val COLON          = ':'  // used in maps
private const val SEMICOLON      = ';'  // used in comments

internal fun Char.isNameChar(): Boolean =
    this.isLetter() || this.isDigit() || this == '_' || this == '.' ||
            this == '@' || this == '/' || this == '+' || this == '-' ||
            this == '=' || this == '$' || this == '%' ||
            this == '!' || this == '?' || this == '*' || this == '#' ||
            this == '&' || this == '~' || this == '^' || this == '|' ||
            this == '<' || this == '>' || this == ':'

class ParserException(message: String, pos: Pos) : Exception("$message at $pos")

class MuParser(val input: CharInput<TextAndPosSpan>) {
    private inline fun require(value: Boolean, lazyMessage: () -> String): Unit {
        if (!value) throw ParserException(lazyMessage(), input.pos())
    }
    private inline fun error(message: String): Nothing {
        throw ParserException(message, input.pos())
    }

    fun skipWS() {
        while (true) {
            if (input.current.isWhitespace()) input.advance()
            else if (input.current == SEMICOLON) {
                while (input.current != '\n' && input.current != CharInput.EOB)
                    input.advance()
            } else break
        }
    }

    fun parseAll(): List<MuParsedExpr> {
        val result = mutableListOf<MuParsedExpr>()

        while (true) {
            skipWS()
            if (input.current == CharInput.EOB) break
            val expr = parseExpression()
            result.add(expr)
        }

        return result
    }

    fun parseExpression(): MuParsedExpr {
        skipWS()
        if (input.current == BRACE_OPEN) return parseMap()
        if (input.current == BRACKET_OPEN) return parseList()
        if (input.current == PAREN_OPEN) return parseGroup()
        if (input.current == QUOTE_SINGLE || input.current == QUOTE_DOUBLE)
            return parseString()
        if (input.current.isNameChar()) return parseRealOrIntegerOrSymbol()!!
        error("Expected expression, found '${input.current}'")
    }

    fun parseName(start: CharInput.Mark): MuParsedExpr.Atom {
        require(input.current.isNameChar()) {
            "Expected name character while parsing name"
        }
        while (input.current.isNameChar()) input.advance()
        val s = input.capture(start)
        return MuParsedExpr.Atom(Spanned(s.raw, s))
    }

    fun parseRealOrIntegerOrSymbol(): MuParsedExpr? {
        val start = input.mark()
        val numChars = StringBuilder()
        fun advance() {
            if (input.current != '_' && input.current != '%')
                numChars.append(input.current)
            input.advance()
        }

        require(input.current.isNameChar())

        // State 0: +/- and the first digit
        if (input.current == '-' || input.current == '+') {
            advance()
        }
        else if (input.current.isNameChar() && !input.current.isDigit()) {
            return parseName(start)
        }

        if (!input.current.isDigit()) {
            input.reset(start)
            return parseName(start)
        }
        advance()

        // State 1: the rest of the digits

        while (true) {
            if (input.current.isDigit()) {
                advance()
            } else if (input.current == '_') {
                advance()
                while (input.current == '_') advance()

                if (!input.current.isDigit()) {
                    if (input.current.isNameChar()) {
                        return parseName(start)
                    }
                    input.reset(start)
                    return null
                }
                advance()
            } else {
                break
            }
        }

        // State 2: either a decimal point or an exponent or the end
        if (input.current == '/') {
            advance()
            if (!input.current.isDigit()) {
                input.reset(start)
                return parseName(start)
            }
            advance()
            while (true) {
                if (input.current.isDigit()) {
                    advance()
                } else if (input.current == '_') {
                    advance()
                    while (input.current == '_') advance()

                    if (!input.current.isDigit()) {
                        if (input.current.isNameChar()) {
                            return parseName(start)
                        }
                        input.reset(start)
                        return null
                    }
                    advance()
                } else {
                    break
                }
            }

            val s = input.capture(start)
            return MuParsedExpr.Rational(Spanned(Rational.parse(numChars.toString()), s))
        }

        if (!input.current.isNameChar()) {
            // Integer
            val s = input.capture(start)
            return MuParsedExpr.Integer(Spanned(numChars.toString().toBigInteger(), s))
        }

        var hasExponent = false
        var hasDecimal = false

        if (input.current == '.') {
            advance()

            // NOTE: SAME AS ABOVE
            while (true) {
                if (input.current.isDigit()) {
                    advance()
                } else if (input.current == '_') {
                    advance()
                    while (input.current == '_') advance()

                    if (!input.current.isDigit()) {
                        input.reset(start)
                        return parseName(start)
                    }
                    advance()
                } else {
                    break
                }
            }

            hasDecimal = true
        }

        if (input.current == '%') {
            advance()
            if (input.current.isNameChar()) {
                return parseName(start)
            }

            val s = input.capture(start)
            val value = numChars.toString().toDouble() / 100.0
            return MuParsedExpr.Real(Spanned(value, s))
        }

        if (input.current == 'e' || input.current == 'E') {
            advance()
            if (input.current == '+' || input.current == '-') advance()
            if (!input.current.isDigit()) {
                input.reset(start)
                return parseName(start)
            }
            while (input.current.isDigit()) advance()

            hasExponent = true
        }

        if (input.current.isNameChar()) {
            return parseName(start)
        }

        val s = input.capture(start)
        if (hasDecimal) {
            return MuParsedExpr.Real(Spanned(numChars.toString().toDouble(), s))
        } else {
            return MuParsedExpr.Integer(Spanned(numChars.toString().toBigInteger(), s))
        }
    }

    fun parseList(): MuParsedExpr.List {
        // []
        // [1]
        // [1 2]
        // [1 2 3]

        require(input.current == BRACKET_OPEN)
        input.advance()

        val result = mutableListOf<MuParsedExpr>()
        while (true) {
            skipWS()
            if (input.current == BRACKET_CLOSE) {
                input.advance()
                skipWS()
                break
            }
            val expr = parseExpression()
            result.add(expr)
            skipWS()
            if (input.current == COMMA) {
                input.advance()
                skipWS()
            }
        }

        return MuParsedExpr.List(result)
    }

    fun parseGroup(): MuParsedExpr.Seq {
        // ()
        // (a)
        // (a b)
        // (a b c)
        // (a (b c))
        // (a (b c) d)
        require(input.current == PAREN_OPEN)
        input.advance()

        val result = mutableListOf<MuParsedExpr>()
        while (true) {
            skipWS()
            if (input.current == PAREN_CLOSE) {
                input.advance()
                skipWS()
                break
            }
            val expr = parseExpression()
            result.add(expr)
        }

        return MuParsedExpr.Seq(result)
    }

    fun parseMap(): MuParsedExpr.Map {
        // {}
        // {a: 1, b: 2}
        // {a: 1, b: 2,}
        // {a : 1, b : 2,}
        // {(a) : [1], (f x) : {},}

        require(input.current == BRACE_OPEN)
        input.advance()

        val result = mutableListOf<Pair<MuParsedExpr, MuParsedExpr>>()
        while (true) {
            skipWS()
            if (input.current == BRACE_CLOSE) {
                input.advance()
                skipWS()
                break
            }
            var key = parseExpression()

            skipWS()

            if (input.current != ':') {
                if (key is MuParsedExpr.Atom) {
                    if (key.name.value.endsWith(":")) {
                        val newValue = key.name.value.dropLast(1)
                        val oldSpan = key.name.span
                        val newSpan = TextAndPosSpan(oldSpan.raw.dropLast(1), oldSpan.start,
                            Pos(oldSpan.end.line, oldSpan.end.column - 1, oldSpan.end.index - 1))
                        key = MuParsedExpr.Atom(Spanned(newValue, newSpan))
                    } else {
                        error("Expected ':' or ':' at the end of atom")
                    }
                } else error("Expected ':' or ':' at the end of atom")
            } else input.advance()
            skipWS()
            val value = parseExpression()
            result.add(key to value)
            skipWS()
            if (input.current == BRACE_CLOSE) {
                input.advance()
                skipWS()
                break
            }
            if (input.current != COMMA) {
                error("Expected ',' or '}' while parsing map, found '${input.current}'")
            }
            input.advance()
        }

        return MuParsedExpr.Map(result)
    }

    fun parseString(): MuParsedExpr.String {
        // "\"\"", "\"a\"", "'a'", "\"a b\"", "'abc'", "'\"'",

        require(input.current == QUOTE_SINGLE || input.current == QUOTE_DOUBLE) {
            "Expected quote while parsing string, found '${input.current}'"
        }
        val quote = input.current
        input.mark()
        input.advance()

        val sb = StringBuilder()
        while (true) {
            if (input.current == quote) {
                input.advance()
                break
            }
            if (input.current == CharInput.EOB) {
                error("Expected '$quote' while parsing string, found end of buffer")
            }
            if (input.current == '\\') {
                input.advance()
                when (input.current) {
                    '\\' -> {
                        sb.append('\\')
                        input.advance()
                    }
                    '\'' -> {
                        sb.append('\'')
                        input.advance()
                    }
                    '"' -> {
                        sb.append('"')
                        input.advance()
                    }
                    'n' -> {
                        sb.append('\n')
                        input.advance()
                    }
                    'r' -> {
                        sb.append('\r')
                        input.advance()
                    }
                    't' -> {
                        sb.append('\t')
                        input.advance()
                    }
                    'u' -> {
                        input.advance()
                        val start = input.mark()
                        if (input.current != '{') {
                            error("Expected '{' after '\\u' while parsing string")
                        }
                        input.advance()
                        var codepoint = 0
                        while (true) {
                            if (input.current == '}') {
                                input.advance()
                                break
                            }
                            if (!input.current.isHexDigit()) {
                                error("Expected hex digit while parsing string")
                            }
                            codepoint = codepoint * 16 + input.current.toString().toInt(16)
                            input.advance()
                        }
                        sb.append(codepoint.toChar())
                    }
                    else -> {
                        sb.append('\\')
                        sb.append(input.current)
                        input.advance()
                    }
                }
            } else {
                sb.append(input.current)
                input.advance()
            }
        }

        val span = input.capture()
        return MuParsedExpr.String(Spanned(sb.toString(), span))
    }

    companion object {
        fun parse(input: CharInput<TextAndPosSpan>): List<MuParsedExpr> = MuParser(input).parseAll()
        fun parse(input: String): List<MuParsedExpr> = parse(CharInput.withTextAndPosSpans(input))
        fun parseFile(path: File): List<MuParsedExpr> = parse(path.readText())
        fun parseFile(path: String): List<MuParsedExpr> = parseFile(File(path))
        fun parseFile(path: Path): List<MuParsedExpr> = parseFile(path.toFile())
    }
}
