package com.resderx.rac.network.call.completions.basic

import kotlinx.serialization.Serializable

@Serializable
sealed interface CompletionsApiResponseChoicesLogprobsContentTopLogprobsBasic {
    val token: String
    val logprob: Number
    val bytes: List<Int>?
}