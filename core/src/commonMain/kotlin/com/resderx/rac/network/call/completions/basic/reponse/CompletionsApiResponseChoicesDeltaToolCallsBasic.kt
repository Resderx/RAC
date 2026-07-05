package com.resderx.rac.network.call.completions.basic.reponse

import kotlinx.serialization.Serializable

@Serializable
sealed interface CompletionsApiResponseChoicesDeltaToolCallsBasic {
    val index: Int
    val id: String
    val type: String
    val function: CompletionsApiResponseChoicesDeltaToolCallsFunctionBasic
}
