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

import top.resderx.rac.api.completions.CompletionsStreamChunk
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeSseParseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parseStreamChunkWithContent() {
        val data = """{"id":"1","model":"gpt-4","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}"""
        val chunk = json.decodeFromString(CompletionsStreamChunk.serializer(), data)
        assertEquals("Hello", chunk.choices[0].delta.content)
        assertEquals("assistant", chunk.choices[0].delta.role)
    }

    @Test
    fun parseStreamChunkWithReasoningContent() {
        val data = """{"id":"1","model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"answer","reasoning_content":"thinking"},"finish_reason":null}]}"""
        val chunk = json.decodeFromString(CompletionsStreamChunk.serializer(), data)
        assertEquals("thinking", chunk.choices[0].delta.reasoningContent)
    }

    @Test
    fun parseStreamChunkWithNullFields() {
        val data = """{"id":"1","model":"gpt-4","choices":[{"index":0,"delta":{},"finish_reason":null}]}"""
        val chunk = json.decodeFromString(CompletionsStreamChunk.serializer(), data)
        assertNull(chunk.choices[0].delta.content)
        assertNull(chunk.choices[0].delta.role)
    }

    @Test
    fun parseStreamChunkWithUsage() {
        val data = """{"id":"1","model":"gpt-4","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}"""
        val chunk = json.decodeFromString(CompletionsStreamChunk.serializer(), data)
        assertEquals(7L, chunk.usage?.totalTokens)
        assertEquals("stop", chunk.choices[0].finishReason)
    }

    @Test
    fun parseStreamChunkWithEmptyChoices() {
        val data = """{"id":"1","model":"gpt-4"}"""
        val chunk = json.decodeFromString(CompletionsStreamChunk.serializer(), data)
        assertTrue(chunk.choices.isEmpty())
    }

    @Test
    fun ignoreUnknownFieldsInStreamChunk() {
        val data = """{"id":"1","model":"gpt-4","unknown_field":"value","choices":[]}"""
        val chunk = json.decodeFromString(CompletionsStreamChunk.serializer(), data)
        assertEquals("1", chunk.id)
        assertTrue(chunk.choices.isEmpty())
    }
}
