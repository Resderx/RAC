package com.resderx.rac.network.call.completions.basic.reponse

import kotlinx.serialization.Serializable

@Serializable
sealed interface CompletionsApiResponseChoicesDeltaToolCallsFunctionBasic {
    val name: String
    val arguments: String
}
