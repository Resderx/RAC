/*
 * Copyright 2026 Resderx
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package top.resderx.rac

import kotlinx.serialization.json.Json
import top.resderx.rac.api.anthropic.AnthropicResponse
import top.resderx.rac.api.anthropic.ContentBlock
import top.resderx.rac.api.completions.Choice
import top.resderx.rac.api.completions.CompletionsResponse
import top.resderx.rac.api.completions.CompletionsStreamChunk
import top.resderx.rac.api.completions.ResponseMessage
import top.resderx.rac.api.responses.OutputItem
import top.resderx.rac.api.responses.ResponseContent
import top.resderx.rac.api.responses.ResponsesResponse
import top.resderx.rac.dsl.toAIMessage
import top.resderx.rac.dsl.toFinishReason
import top.resderx.rac.messages.FinishReason
import top.resderx.rac.messages.Usage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * mingwX64 原生平台序列化与映射测试。
 *
 * - 作用：验证 JSON 序列化/反序列化与响应映射在 Windows 原生平台正确工作
 * - 必要性：Kotlin/Native 的内存模型与序列化器实现可能与 JVM 有差异，需独立验证
 * - 设计：纯数据测试，不触网，验证反序列化与 toAIMessage 映射
 * - 边缘：空 choices、null content、未知 finishReason
 */
class NativeSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parseCompletionsResponse() {
        val data = """{"id":"1","model":"gpt-4","choices":[{"index":0,"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}"""
        val resp = json.decodeFromString(CompletionsResponse.serializer(), data)
        assertEquals("Hi", resp.choices[0].message.content)
        assertEquals(7L, resp.usage?.totalTokens)
    }

    @Test
    fun parseStreamChunkWithReasoningContent() {
        val data = """{"id":"1","model":"deepseek-chat","choices":[{"index":0,"delta":{"role":"assistant","content":"answer","reasoning_content":"thinking"},"finish_reason":null}]}"""
        val chunk = json.decodeFromString(CompletionsStreamChunk.serializer(), data)
        assertEquals("thinking", chunk.choices[0].delta.reasoningContent)
    }

    @Test
    fun completionsResponseToAIMessage() {
        val resp = CompletionsResponse(
            id = "1",
            model = "gpt-4",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ResponseMessage(role = "assistant", content = "Hello"),
                    finishReason = "stop",
                ),
            ),
            usage = Usage(promptTokens = 5, completionTokens = 2, totalTokens = 7),
        )
        val ai = resp.toAIMessage()
        assertEquals("Hello", ai.content)
        assertEquals(FinishReason.STOP, ai.finishReason)
        assertEquals(7L, ai.usage?.totalTokens)
    }

    @Test
    fun completionsResponseWithNullContent() {
        val resp = CompletionsResponse(
            id = "1",
            model = "gpt-4",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ResponseMessage(role = "assistant", content = null),
                    finishReason = "length",
                ),
            ),
        )
        val ai = resp.toAIMessage()
        assertEquals("", ai.content)
        assertEquals(FinishReason.LENGTH, ai.finishReason)
    }

    @Test
    fun anthropicResponseToAIMessage() {
        val resp = AnthropicResponse(
            id = "1",
            model = "claude-3",
            content = listOf(ContentBlock.Text(text = "Hello from Claude")),
            stopReason = "end_turn",
        )
        val ai = resp.toAIMessage()
        assertEquals("Hello from Claude", ai.content)
    }

    @Test
    fun responsesResponseToAIMessage() {
        val resp = ResponsesResponse(
            id = "1",
            model = "gpt-4o",
            output = listOf(
                OutputItem.MessageOutput(
                    id = "msg1",
                    content = listOf(ResponseContent(type = "output_text", text = "Hello")),
                ),
            ),
        )
        val ai = resp.toAIMessage()
        assertEquals("Hello", ai.content)
    }

    @Test
    fun toFinishReasonMapsCorrectly() {
        assertEquals(FinishReason.STOP, "stop".toFinishReason())
        assertEquals(FinishReason.LENGTH, "length".toFinishReason())
        assertEquals(FinishReason.TOOL_CALLS, "tool_calls".toFinishReason())
        assertEquals(FinishReason.CONTENT_FILTER, "content_filter".toFinishReason())
        assertEquals(FinishReason.UNKNOWN, null.toFinishReason())
        assertEquals(FinishReason.UNKNOWN, "weird".toFinishReason())
    }
}
