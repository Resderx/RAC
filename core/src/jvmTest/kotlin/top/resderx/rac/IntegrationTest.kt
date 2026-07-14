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

import com.resderx.rac.dsl.llm
import com.resderx.rac.dsl.deepseek
import com.resderx.rac.dsl.openai
import com.resderx.rac.dsl.anthropic
import com.resderx.rac.messages.AIMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Ignore

/**
 * 集成测试脚手架——默认跳过，仅当环境变量 RAC_INTEGRATION_TEST=true 时运行。
 *
 * - 作用：对 DeepSeek/OpenAI/Anthropic 三家进行真实 API 调用验证
 * - 必要性：MockEngine 测试无法覆盖真实网络与供应商协议差异，需集成测试兜底
 * - 设计：通过 RAC_INTEGRATION_TEST 与 RAC_<PROVIDER>_API_KEY 环境变量控制；未开启时早期 return
 * - 边缘：未设置 Key 时对应用例跳过；网络不可用时用例失败（不 mock）
 */
class IntegrationTest {

    /** 是否启用集成测试。 */
    private val enabled: Boolean
        get() = System.getenv("RAC_INTEGRATION_TEST")?.equals("true", ignoreCase = true) == true

    /** DeepSeek API Key（从环境变量 RAC_DEEPSEEK_API_KEY 读取）。 */
    private val deepseekKey: String? get() = System.getenv("RAC_DEEPSEEK_API_KEY")

    /** OpenAI API Key（从环境变量 RAC_OPENAI_API_KEY 读取）。 */
    private val openaiKey: String? get() = System.getenv("RAC_OPENAI_API_KEY")

    /** Anthropic API Key（从环境变量 RAC_ANTHROPIC_API_KEY 读取）。 */
    private val anthropicKey: String? get() = System.getenv("RAC_ANTHROPIC_API_KEY")

    @Test
    fun deepseekChatCompletion() = runTest {
        if (!enabled || deepseekKey == null) return@runTest
        val ai = llm {
            providers {
                deepseek {
                    apiKey(deepseekKey)
                }
            }
        }
        val resp: AIMessage = ai.chat {
            user("Say 'hello' in one word.")
        }
        assertTrue(resp.content.isNotEmpty(), "DeepSeek response content should not be empty")
    }

    @Test
    fun deepseekChatStream() = runTest {
        if (!enabled || deepseekKey == null) return@runTest
        val ai = llm {
            providers {
                deepseek {
                    apiKey(deepseekKey)
                }
            }
        }
        val chunks = ai.chatStream {
            user("Say 'hello' in one word.")
        }.toList()
        assertTrue(chunks.isNotEmpty(), "DeepSeek stream should emit at least one chunk")
    }

    @Test
    fun openaiChatCompletion() = runTest {
        if (!enabled || openaiKey == null) return@runTest
        val ai = llm {
            providers {
                openai {
                    apiKey(openaiKey)
                }
            }
        }
        val resp: AIMessage = ai.chat {
            user("Say 'hello' in one word.")
        }
        assertTrue(resp.content.isNotEmpty(), "OpenAI response content should not be empty")
    }

    @Test
    fun anthropicChatCompletion() = runTest {
        if (!enabled || anthropicKey == null) return@runTest
        val ai = llm {
            providers {
                anthropic {
                    apiKey(anthropicKey)
                }
            }
        }
        val resp: AIMessage = ai.chat {
            user("Say 'hello' in one word.")
        }
        assertTrue(resp.content.isNotEmpty(), "Anthropic response content should not be empty")
    }
}
