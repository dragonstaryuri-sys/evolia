package me.rerere.rikkahub.core.data.model

import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
fun String.replaceRegexes(
    assistant: Assistant?,
    scope: AssistantAffectScope,
    visual: Boolean = false
): String {
    if (assistant == null) return this
    if (assistant.regexes.isEmpty()) return this
    return assistant.regexes.fold(this) { acc, regex ->
        if (regex.enabled && regex.visualOnly == visual && regex.affectingScope.contains(scope)) {
            try {
                acc.replace(
                    regex = Regex(regex.findRegex),
                    replacement = regex.replaceString,
                )
            } catch (e: Exception) {
                e.printStackTrace()
                acc
            }
        } else {
            acc
        }
    }
}

fun Lorebook.toTavernCharacterBook(): TavernCharacterBook {
    return TavernCharacterBook(
        name = name,
        description = description,
        entries = entries.mapIndexed { index, entry ->
            TavernCharacterBookEntry(
                keys = entry.keywords.ifEmpty { listOf(entry.name) },
                content = entry.prompt,
                enabled = entry.enabled,
                insertion_order = index,
                case_sensitive = entry.caseSensitive,
                priority = 10,
                position = when (entry.injectionPosition) {
                    InjectionPosition.BEFORE_SYSTEM -> "before_char"
                    else -> "after_char"
                }
            )
        }
    )
}

fun TavernCharacterBook.toLorebook(): Lorebook {
    return Lorebook(
        name = name,
        description = description,
        entries = entries.sortedBy { it.insertion_order }.map { it.toLorebookEntry() }
    )
}

fun Lorebook.toSillyTavernWorldInfo(): SillyTavernWorldInfo {
    return SillyTavernWorldInfo(
        name = name,
        description = description,
        entries = entries.mapIndexed { index, entry ->
            index.toString() to SillyTavernWorldInfoEntry(
                uid = index,
                key = entry.keywords.ifEmpty { listOf(entry.name) },
                comment = entry.name,
                content = entry.prompt,
                constant = entry.activationType == LorebookActivationType.ALWAYS,
                selective = entry.activationType == LorebookActivationType.KEYWORDS,
                order = index * 10 + 100,
                position = when (entry.injectionPosition) {
                    InjectionPosition.BEFORE_SYSTEM -> 1
                    InjectionPosition.AT_DEPTH -> 4
                    else -> 0
                },
                disable = !entry.enabled,
                depth = entry.depth,
                scanDepth = entry.scanDepth,
                caseSensitive = entry.caseSensitive,
                displayIndex = index
            )
        }.toMap()
    )
}

fun SillyTavernWorldInfo.toLorebook(): Lorebook {
    return Lorebook(
        name = name.ifEmpty { originalData?.name ?: "" },
        description = description.ifEmpty { originalData?.description ?: "" },
        entries = entries.values
            .sortedBy { it.displayIndex }
            .map { it.toLorebookEntry() }
    )
}
