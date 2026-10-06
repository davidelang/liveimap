package org.dlang.liveimap.ui.contacts

import org.dlang.liveimap.session.SelectedAddress

data class CompletionEntry(
    val nickname: String,
    val displayName: String,
    val email: String,
)

data class CompletionSource(
    val label: String,
    val entries: List<CompletionEntry>,
)

data class AddressSuggestion(
    val sourceLabel: String,
    val nickname: String,
    val displayName: String,
    val email: String,
    val distribution: Boolean,
    val members: List<SelectedAddress>,
)

fun completeAddress(typed: String, sources: List<CompletionSource>): List<AddressSuggestion> {
    val needle = typed.trim()
    if (needle.isEmpty()) return emptyList()
    val exact = ArrayList<AddressSuggestion>()
    val contains = ArrayList<AddressSuggestion>()
    for (source in sources) {
        for (entry in source.entries) {
            val suggestion = suggestionFor(source.label, entry)
            if (entry.nickname.equals(needle, ignoreCase = true)) {
                exact.add(suggestion)
                continue
            }
            if (
                entry.displayName.contains(needle, ignoreCase = true) ||
                entry.email.contains(needle, ignoreCase = true)
            ) {
                contains.add(suggestion)
            }
        }
    }
    exact.addAll(contains)
    return exact
}

private fun suggestionFor(label: String, entry: CompletionEntry): AddressSuggestion {
    val distribution = isDistributionAddress(entry.email)
    val members = if (distribution) {
        pickedAddresses(
            AlpineEntry(
                nickname = entry.nickname,
                fullname = entry.displayName,
                address = entry.email.trim(),
                fcc = "",
                comments = "",
            ),
        )
    } else {
        emptyList()
    }
    return AddressSuggestion(
        sourceLabel = label,
        nickname = entry.nickname,
        displayName = entry.displayName,
        email = entry.email,
        distribution = distribution,
        members = members,
    )
}

private fun isDistributionAddress(address: String): Boolean {
    val trimmed = address.trim()
    return trimmed.length >= 2 && trimmed.startsWith("(") && trimmed.endsWith(")")
}
