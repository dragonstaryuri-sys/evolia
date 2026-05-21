package me.rerere.ai.core

enum class ReasoningLevel(
    val budgetTokens: Int,
    val effort: String
) {
    OFF(0, "minimal"),
    AUTO(-1, "auto"),
    LOW(1024, "low"),
    MEDIUM(16_000, "medium"),
    HIGH(32_000, "high");

    val isEnabled: Boolean
        get() = this != OFF

    companion object {
        fun fromBudgetTokens(budgetTokens: Int?): ReasoningLevel {
            if (budgetTokens == null) return AUTO
            if (budgetTokens == 0) return OFF
            return entries.filter { it != AUTO && it != OFF }
                .minByOrNull { kotlin.math.abs(it.budgetTokens - budgetTokens) } ?: AUTO
        }
    }
}
