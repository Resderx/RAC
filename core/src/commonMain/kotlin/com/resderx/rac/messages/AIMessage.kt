package com.resderx.rac.messages

import kotlinx.serialization.Serializable

@Serializable
data class AIMessage(
    val reasonContent: String,
    val content: String,
    val toolCallContent: String,
)
