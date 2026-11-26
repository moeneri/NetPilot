package dev.netpilot.i18n

import androidx.annotation.StringRes

internal data class RuntimeTextResource(@param:StringRes val id: Int, val template: Boolean, val translateArguments: Boolean)

/** Display translations for messages from older Root daemons; persisted logs stay intact. */
internal object RuntimeText {
    private val placeholder = Regex("%([1-9][0-9]*)\\\$[sd]")
    private val exception = Regex("^([A-Za-z0-9_.$]+(?:Exception|Error)): (.*)$", RegexOption.DOT_MATCHES_ALL)
    private val statusCode = Regex("^([A-Z][A-Z0-9_]+): (.*)$", RegexOption.DOT_MATCHES_ALL)
    private data class Rule(val pattern: Regex, val target: String, val indexes: List<Int>, val translateArguments: Boolean)
    private data class Catalog(val literals: Map<String, String>, val rules: List<Rule>)
    private val catalogs = mutableMapOf<Boolean, Catalog>()

    @Synchronized
    private fun catalog(strings: LocalizedStrings): Catalog = catalogs.getOrPut(strings.locale.language == "zh") {
        val sourceLanguage = if (strings.locale.language == "zh") AppLanguage.ENGLISH else AppLanguage.CHINESE
        val source = AppLocale.context(strings.context, sourceLanguage).resources
        val target = strings.context.resources
        val literals = linkedMapOf<String, String>()
        val rules = mutableListOf<Rule>()
        for (resource in runtimeTextResources) {
            val original = source.getString(resource.id)
            val translated = target.getString(resource.id)
            if (!resource.template) literals.putIfAbsent(original, translated)
            else {
                val indexes = mutableListOf<Int>()
                var previous = 0
                val pattern = buildString {
                    for (match in placeholder.findAll(original)) {
                        append(Regex.escape(original.substring(previous, match.range.first)))
                        append("(.*?)")
                        indexes += match.groupValues[1].toInt()
                        previous = match.range.last + 1
                    }
                    append(Regex.escape(original.substring(previous)))
                }
                rules += Rule(Regex(pattern, RegexOption.DOT_MATCHES_ALL), translated, indexes, resource.translateArguments)
            }
        }
        Catalog(literals, rules)
    }

    fun translate(strings: LocalizedStrings, text: String): String = translate(strings, text, 0)

    private fun translate(strings: LocalizedStrings, text: String, depth: Int): String {
        if (text.isEmpty() || depth >= 6) return text
        val catalog = catalog(strings)
        catalog.literals[text]?.let { return it }
        for (rule in catalog.rules) {
            val match = rule.pattern.matchEntire(text) ?: continue
            val arguments = rule.indexes.zip(match.groupValues.drop(1)).toMap()
            return placeholder.replace(rule.target) { token ->
                val value = arguments[token.groupValues[1].toInt()].orEmpty()
                if (rule.translateArguments) translate(strings, value, depth + 1) else value
            }
        }
        exception.matchEntire(text)?.let { match -> return "${match.groupValues[1]}: ${translate(strings, match.groupValues[2], depth + 1)}" }
        statusCode.matchEntire(text)?.let { match -> return "${match.groupValues[1]}: ${translate(strings, match.groupValues[2], depth + 1)}" }
        return if ('\n' in text) text.split('\n').joinToString("\n") { translate(strings, it, depth + 1) } else text
    }
}
