package com.resderx.rac.network.call.completions.basic.reponse

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface CompletionsApiResponseBasic {
    val id: String

    @SerialName("object")
    val obj: String
    val model: String

    @SerialName("system_fingerprint")
    val systemFingerprint: String?
    val created: Long
    val choices: List<CompletionsApiResponseChoicesBasic>
    val usage: CompletionsApiResponseUsageBasic?

    @SerialName("service_tier")
    val serviceTier: String?
}
