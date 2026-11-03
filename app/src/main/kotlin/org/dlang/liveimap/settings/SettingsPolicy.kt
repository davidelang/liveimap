package org.dlang.liveimap.settings

import org.dlang.liveimap.session.existingMailSession

data class PolicyLine(
    val key: String,
    val text: String,
    val needsPick: Boolean = false,
    val options: List<Pair<String, String>> = emptyList(),
    val clean: (Settings, String?) -> Settings,
)

fun policyIssues(settings: Settings, accountId: String?): List<PolicyLine> {
    if (InstalledTree.leaves.isEmpty()) installGeneratedSettings()
    val lines = ArrayList<PolicyLine>()
    val fresh = Settings()
    for (entry in settings.unknownSettings) {
        val key = entry.substringBefore('\n')
        val spec = settingsRegistry[key]
        if (spec == null) {
            lines.add(PolicyLine(key, "$key is not a setting. Clean removes it.") { current, _ ->
                current.withoutPreserved(key)
            })
            continue
        }
        val defaultText = fresh.getSetting(key)
        lines.add(PolicyLine(key, "$key is not the expected type. Clean sets $defaultText.") { current, _ ->
            current.withoutPreserved(key).withSetting(key, SettingValue(defaultText))
        })
    }
    for ((key, spec) in settingsRegistry) {
        if (spec.leaf.derived) continue
        val leaf = spec.leaf
        if (leaf.typeName == "number" || leaf.typeName == "Int" || leaf.typeName == "Long") {
            if (leaf.many) {
                val numbers = settings.getSetting(key).let { text ->
                    if (text.isEmpty() || text == "[]") emptyList() else readIntList(parseJson(text))
                }
                val clean = when (leaf.order) {
                    "increasing" -> numbers == numbers.sorted()
                    "sorted" -> numbers == numbers.sorted().distinct()
                    else -> true
                }
                if (!clean) {
                    val sorted = if (leaf.order == "sorted") numbers.sorted().distinct() else numbers.sorted()
                    val sentence = if (leaf.order == "sorted") {
                        "$key is not in order. Clean sorts it and drops duplicates."
                    } else {
                        "$key is not in increasing order. Clean sorts it from low to high."
                    }
                    lines.add(PolicyLine(key, sentence) { current, _ ->
                        current.withSetting(key, SettingValue(encodeJson(intListJson(sorted))))
                    })
                }
                continue
            }
            val raw = settings.getSetting(key)
            val number = raw.toLongOrNull() ?: continue
            val reason = numberRejection(key, leaf, number)
            if (reason != null) {
                val defaultText = fresh.getSetting(key)
                lines.add(PolicyLine(key, "$key is outside its limits. Clean sets $defaultText.") { current, _ ->
                    current.withSetting(key, SettingValue(defaultText))
                })
            }
        }
        if ((leaf.typeName == "text" || leaf.typeName == "String") && leaf.many && leaf.order == "sorted") {
            val values = readStringList(parseJson(settings.getSetting(key)))
            if (values != values.sorted() || values.size != values.distinct().size) {
                val sorted = values.map { it }.sorted().distinct()
                lines.add(PolicyLine(key, "$key is not in order. Clean sorts it and drops duplicates.") { current, _ ->
                    current.withSetting(key, SettingValue(encodeJson(stringListJson(sorted))))
                })
            }
        }
    }
    val sessionId = accountId
    val session = if (sessionId.isNullOrEmpty()) null else existingMailSession(sessionId)
    val caps = session?.featureCaps
    val uidPlus = serverCapAllows(settings.jmapFolders, session != null, caps?.uidPlus == true)
    val trashKnown = settings.trashMailbox.isNotEmpty()
    if (settings.deletePolicy == DeletePolicy.DeletePermanently && !uidPlus) {
        lines.add(PolicyLine("deletePolicy", "Saved delete choice needs UIDPLUS. Clean sets Mark deleted.") { current, _ ->
            current.copy(deletePolicy = DeletePolicy.MarkDeleted)
        })
    }
    if (settings.deletePolicy == DeletePolicy.MoveToTrash && !trashKnown) {
        lines.add(PolicyLine("deletePolicy", "Saved delete choice needs a trash mailbox. Clean sets Mark deleted.") { current, _ ->
            current.copy(deletePolicy = DeletePolicy.MarkDeleted)
        })
    }
    lines.addAll(scopeDisagreementLines(settings, accountId))
    return lines
}
