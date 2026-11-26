package dev.netpilot.core.root

object ShellEscaping {
    fun quote(value: String): String {
        require('\u0000' !in value) { "NUL is not permitted in shell arguments" }
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
    fun command(arguments: List<String>) = arguments.joinToString(" ", transform = ::quote)
}
