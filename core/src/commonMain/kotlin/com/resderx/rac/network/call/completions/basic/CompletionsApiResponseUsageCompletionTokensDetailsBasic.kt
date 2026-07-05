package com.resderx.rac.network.call.completions.basic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface CompletionsApiResponseUsageCompletionTokensDetailsBasic {
    @SerialName("reasoning_tokens")
    val reasoningTokens: Int
}