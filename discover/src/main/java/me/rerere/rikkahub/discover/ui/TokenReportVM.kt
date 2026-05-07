package me.rerere.rikkahub.discover.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.rerere.rikkahub.core.data.db.entity.DailyUsageSummary
import me.rerere.rikkahub.core.data.db.entity.TokenUsageEntity
import me.rerere.rikkahub.core.data.model.Assistant
import me.rerere.rikkahub.core.data.repository.ConversationRepository
import java.time.LocalDate

data class AgentTokenRanking(
    val assistantId: String,
    val assistantName: String,
    val prompt: Int,
    val completion: Int,
    val cached: Int,
    val total: Int
)

class TokenReportVM(
    private val conversationRepository: ConversationRepository,
    private val assistantsFlow: StateFlow<List<Assistant>>
) : ViewModel() {

    // 获取最近7天的所有详细数据（用于排行）
    val allUsageData: StateFlow<List<TokenUsageEntity>> = conversationRepository
        .getAllRecentTokenUsageFlow(days = 7)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 每日总计汇总（用于报表列表），强制只显示最近 7 天
    val dailyHistory: StateFlow<List<DailyUsageSummary>> = conversationRepository
        .getDailyTotalUsageFlow(days = 7)
        .map { it.take(7) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 智能体消耗排行
    val agentRankings: StateFlow<List<AgentTokenRanking>> = combine(
        allUsageData,
        assistantsFlow
    ) { usage, assistants ->
        val assistantMap = assistants.associateBy { it.id.toString() }

        usage.groupBy { it.assistantId }
            .map { (id, logs) ->
                val assistant = assistantMap[id]
                val prompt = logs.sumOf { it.promptTokens }
                val completion = logs.sumOf { it.completionTokens }
                val cached = logs.sumOf { it.cachedTokens }
                AgentTokenRanking(
                    assistantId = id,
                    assistantName = assistant?.name ?: "Unknown",
                    prompt = prompt,
                    completion = completion,
                    cached = cached,
                    total = prompt + completion
                )
            }
            .sortedByDescending { it.total }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 今日实时统计
    val todayTotalUsage: StateFlow<TokenUsageEntity> = allUsageData.map { list ->
        val today = LocalDate.now().toString()
        val todayLogs = list.filter { it.date == today }
        TokenUsageEntity(
            assistantId = "total",
            date = today,
            promptTokens = todayLogs.sumOf { it.promptTokens },
            completionTokens = todayLogs.sumOf { it.completionTokens },
            cachedTokens = todayLogs.sumOf { it.cachedTokens }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TokenUsageEntity(assistantId = "total", date = LocalDate.now().toString()))

    /**
     * 格式化 Token 数值
     * 规则：超过 10000 显示为 k，最多保留一位小数，四舍五入
     */
    fun formatTokenCount(count: Int): String {
        return if (count >= 10000) {
            val kValue = count / 1000.0
            val formatted = "%.1f".format(kValue)
            "${formatted.removeSuffix(".0")}k"
        } else {
            count.toString()
        }
    }
}
