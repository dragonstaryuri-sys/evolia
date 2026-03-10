package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.core.data.model.AssistantSearchMode
import me.rerere.rikkahub.data.sync.BackupCleanupResult

/**
 * Sanitizes settings to clean up deprecated or invalid data.
 * This is particularly useful when restoring from a backup that might contain
 * references to deleted items or invalid configurations.
 */
fun Settings.sanitize(): Pair<Settings, BackupCleanupResult> {
    var cleanupResult = BackupCleanupResult()

    // Sanitize assistants
    val sanitizedAssistants = assistants.map { assistant ->
        var currentAssistant = assistant

        // 1. Check search mode provider index
        // If the provider index is out of bounds for the current search services, reset to Off
        val searchMode = currentAssistant.searchMode
        if (searchMode is AssistantSearchMode.Provider) {
            if (searchMode.index < 0 || searchMode.index >= searchServices.size) {
                currentAssistant = currentAssistant.copy(searchMode = AssistantSearchMode.Off)
                cleanupResult = cleanupResult.copy(
                    invalidSearchModeCount = cleanupResult.invalidSearchModeCount + 1
                )
            }
        }

        // 2. Check orphaned tag references
        // Remove tag IDs that are no longer present in assistantTags
        val validTagIds = assistantTags.map { it.id }.toSet()
        val sanitizedTags = currentAssistant.tags.filter { it in validTagIds }
        if (sanitizedTags.size != currentAssistant.tags.size) {
            cleanupResult = cleanupResult.copy(
                orphanedTagReferences = cleanupResult.orphanedTagReferences + (currentAssistant.tags.size - sanitizedTags.size)
            )
            currentAssistant = currentAssistant.copy(tags = sanitizedTags)
        }

        currentAssistant
    }

    // 3. Check orphaned favorite models
    // Remove model IDs that are no longer present in any provider
    val allModelIds = providers.flatMap { it.models }.map { it.id }.toSet()
    val sanitizedFavoriteModels = favoriteModels.filter { it in allModelIds }
    if (sanitizedFavoriteModels.size != favoriteModels.size) {
        cleanupResult = cleanupResult.copy(
            orphanedModelReferences = cleanupResult.orphanedModelReferences + (favoriteModels.size - sanitizedFavoriteModels.size)
        )
    }

    val sanitizedSettings = this.copy(
        assistants = sanitizedAssistants,
        favoriteModels = sanitizedFavoriteModels
    )

    return sanitizedSettings to cleanupResult
}
