package com.resderx.rac.network.call.completions

import com.resderx.rac.messages.AIMessage
import kotlinx.coroutines.channels.Channel
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

@OptIn(ExperimentalJsExport::class)
@JsExport
suspend fun singleCompletionsApiCallModel(
    urlString: String,
    token: String,
    body: String,
    channel: Channel<String> = Channel(),
    parser: (String) -> String
): AIMessage {
    var reasonContent = ""
    var content = ""
    var toolCallContent = ""
    basicSingleCompletionsApiCallModel(
        urlString = urlString,
        token = token,
        body = body
    ).collect {
        channel.send(it)
        parser(it)
    }
    return AIMessage(
        reasonContent = "",
        content = "",
        toolCallContent = "",
    )
}