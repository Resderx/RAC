package com.resderx.rac.network.call.completions.basic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface CompletionsApiResponseUsageBasic{
    @SerialName("completion_tokens")
    val completionTokens: Int
    @SerialName("prompt_tokens")
    val promptTokens: Int
    @SerialName("total_tokens")
    val totalTokens: Int
    @SerialName("completion_tokens_details")
    val completionTokensDetails: CompletionsApiResponseUsageCompletionTokensDetailsBasic
}