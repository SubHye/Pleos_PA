package com.example.pleos_pa.bridge

/**
 * Checks a request body's JSON syntax before the lenient parser sees it. Android's `org.json`
 * accepts trailing text, duplicate keys, comments and unquoted strings, while the library unit
 * tests run on rejects some of them, so tests alone could not vouch for the device. Accepts one
 * flat object of escape-free strings, integers and arrays of strings, which is every request the
 * contract allows; the parser judges the rest.
 */
object StrictRequestSyntax {
    fun isWellFormed(text: String): Boolean = Scanner(text).body()

    private class Scanner(private val text: String) {
        private var at = 0

        fun body(): Boolean {
            val keys = mutableSetOf<String>()
            space()
            if (!take('{')) return false
            space()
            if (!take('}')) {
                do {
                    space()
                    val key = string() ?: return false
                    if (!keys.add(key)) return false
                    space()
                    if (!take(':')) return false
                    space()
                    if (!value()) return false
                    space()
                } while (take(','))
                if (!take('}')) return false
            }
            space()
            return at == text.length
        }

        private fun value(): Boolean =
            when {
                peek('"') -> string() != null
                peek('[') -> strings()
                else -> integer()
            }

        private fun strings(): Boolean {
            take('[')
            space()
            if (take(']')) return true
            do {
                space()
                string() ?: return false
                space()
            } while (take(','))
            return take(']')
        }

        private fun string(): String? {
            if (!take('"')) return null
            val end = text.indexOf('"', at)
            if (end < 0) return null
            val value = text.substring(at, end)
            if (value.any { it == '\\' || it < ' ' }) return null
            at = end + 1
            return value
        }

        private fun integer(): Boolean {
            val start = at
            if (peek('-')) at++
            while (at < text.length && text[at] in '0'..'9') at++
            val digits = text.substring(start, at).removePrefix("-")
            return digits.isNotEmpty() && (at == text.length || text[at] !in ".eE")
        }

        private fun peek(char: Char) = at < text.length && text[at] == char

        private fun take(char: Char) = peek(char).also { if (it) at++ }

        private fun space() {
            while (at < text.length && text[at] in " \t\r\n") at++
        }
    }
}
