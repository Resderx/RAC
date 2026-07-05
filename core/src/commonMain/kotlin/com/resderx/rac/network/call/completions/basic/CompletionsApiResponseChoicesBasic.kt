package com.resderx.rac.network.call.completions.basic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface CompletionsApiResponseChoicesBasic {
    val index: Int
    val delta: CompletionsApiResponseChoicesDeltaBasic

    @SerialName("finish_reason")
    val finishReason: String?
    val logprobs:CompletionsApiResponseChoicesLogprobsBasic?
}
