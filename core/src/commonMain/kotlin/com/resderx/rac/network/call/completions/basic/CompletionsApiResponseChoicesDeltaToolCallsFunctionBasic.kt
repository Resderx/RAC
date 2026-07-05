package com.resderx.rac.network.call.completions.basic

import kotlinx.serialization.Serializable

@Serializable
sealed interface CompletionsApiResponseChoicesDeltaToolCallsFunctionBasic {
    val name: String
    val arguments: String
}
