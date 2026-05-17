package me.rerere.rikkahub.data.datastore

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import kotlin.uuid.Uuid

val DEFAULT_PROVIDERS = listOf(
    ProviderSetting.OpenAI(
        id = Uuid.parse("77777777-4444-4444-4444-444444444444"),
        name = "SiliconFlow",
        baseUrl = "https://api.siliconflow.cn/v1",
        apiKey = "",
        builtIn = true,
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/user/info",
            resultPath = "data.totalBalance",
        )
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("d5734028-d39b-4d41-9841-fd648d65440e"),
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        apiKey = "",
        builtIn = true,
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/credits",
            resultPath = "data.total_credits - data.total_usage",
        )
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("637e6d0a-1910-449e-862d-068303f27361"),
        name = "智谱 AI",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        apiKey = "",
        builtIn = true,
        balanceOption = BalanceOption(
            enabled = false,
            apiPath = "",
            resultPath = "",
        )
    ),
)
