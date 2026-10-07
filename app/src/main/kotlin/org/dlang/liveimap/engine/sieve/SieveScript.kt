package org.dlang.liveimap.engine.sieve

enum class RuleField {
    From,
    To,
    ListId,
    Subject,
}

data class RuleCriterion(
    val field: RuleField,
    val value: String,
)

enum class SystemFlag {
    Seen,
    Flagged,
}

data class InboundRule(
    val criteria: List<RuleCriterion>,
    val fileInto: String = "",
    val flags: Set<SystemFlag> = emptySet(),
    val redirectTo: String = "",
    val discard: Boolean = false,
)

/** Sieve text only. Does not connect or upload. */
fun emitSieve(rules: List<InboundRule>): String {
    val kept = rules.filter { it.criteria.isNotEmpty() && hasAction(it) }
    if (kept.isEmpty()) return ""
    val text = StringBuilder()
    val requires = ArrayList<String>(2)
    if (kept.any { it.fileInto.isNotBlank() }) requires.add("fileinto")
    if (kept.any { it.flags.isNotEmpty() }) requires.add("imap4flags")
    if (requires.isNotEmpty()) {
        text.append("require [")
        text.append(requires.joinToString(", ") { "\"$it\"" })
        text.append("];\n\n")
    }
    for ((index, rule) in kept.withIndex()) {
        if (index > 0) text.append('\n')
        text.append(renderRule(rule))
        text.append('\n')
    }
    return text.toString()
}

private fun hasAction(rule: InboundRule): Boolean {
    return rule.fileInto.isNotBlank() ||
        rule.flags.isNotEmpty() ||
        rule.redirectTo.isNotBlank() ||
        rule.discard
}

private fun renderRule(rule: InboundRule): String {
    val tests = rule.criteria.joinToString(", ") { renderTest(it) }
    val actions = ArrayList<String>(5)
    if (SystemFlag.Seen in rule.flags) actions.add("addflag \"\\\\Seen\";")
    if (SystemFlag.Flagged in rule.flags) actions.add("addflag \"\\\\Flagged\";")
    if (rule.fileInto.isNotBlank()) actions.add("fileinto ${quote(rule.fileInto)};")
    if (rule.redirectTo.isNotBlank()) actions.add("redirect ${quote(rule.redirectTo)};")
    if (rule.discard) actions.add("discard;")
    val body = actions.joinToString("\n") { "    $it" }
    return "if allof ($tests) {\n$body\n}"
}

private fun renderTest(criterion: RuleCriterion): String {
    val quoted = quote(criterion.value)
    return when (criterion.field) {
        RuleField.From -> "address :is \"from\" $quoted"
        RuleField.To -> "address :is \"to\" $quoted"
        RuleField.ListId -> "header :is \"list-id\" $quoted"
        RuleField.Subject -> "header :contains \"subject\" $quoted"
    }
}

private fun quote(raw: String): String {
    val cleaned = raw.filter { it != '\r' && it != '\n' && it != '\u0000' }
    val escaped = StringBuilder(cleaned.length + 2)
    for (ch in cleaned) {
        when (ch) {
            '\\' -> escaped.append("\\\\")
            '"' -> escaped.append("\\\"")
            else -> escaped.append(ch)
        }
    }
    return "\"$escaped\""
}
