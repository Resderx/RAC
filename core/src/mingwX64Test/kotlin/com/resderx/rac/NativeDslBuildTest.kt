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

package com.resderx.rac

import com.resderx.rac.dsl.llm
import com.resderx.rac.dsl.deepseek
import com.resderx.rac.dsl.openai
import com.resderx.rac.dsl.ollama
import com.resderx.rac.dsl.glm
import com.resderx.rac.dsl.anthropic
import com.resderx.rac.dsl.kimi
import com.resderx.rac.dsl.minimax
import com.resderx.rac.dsl.doubao
import com.resderx.rac.dsl.qwen
import com.resderx.rac.dsl.mimo
import com.resderx.rac.dsl.gemini
import com.resderx.rac.providers.ApiType
import com.resderx.rac.providers.ModelConfig
import com.resderx.rac.providers.ProviderConfig
import com.resderx.rac.providers.ProviderRegistry
import com.resderx.rac.providers.SimpleModelProvider
import com.resderx.rac.exceptions.RACException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * mingwX64 原生平台 DSL 构建与供应商注册测试。
 *
 * - 作用：验证 llm { } DSL 与供应商工厂在 Windows 原生平台正确工作
 * - 必要性：Kotlin/Native 的类加载与反射限制可能影响 DSL，需独立验证
 * - 设计：构建 Llm 实例并断言注册表内容，不触网
 * - 边缘：未注册供应商时 build() 抛异常；registry.get 未找到抛异常
 */
class NativeDslBuildTest {

    @Test
    fun racBuilderRegistersDeepSeek() {
        val ai = llm {
            providers {
                deepseek {
                    apiKey("test-key")
                }
            }
        }
        assertEquals("deepseek", ai.defaultProvider.name)
        assertEquals("https://api.deepseek.com", ai.defaultProvider.baseUrl)
        assertEquals("deepseek-v4-flash", ai.defaultProvider.defaultModel)
        assertEquals(ApiType.COMPLETIONS, ai.defaultProvider.defaultApiType)
    }

    @Test
    fun racBuilderRegistersMultipleProviders() {
        val ai = llm {
            providers {
                openai { apiKey("k1") }
                deepseek { apiKey("k2") }
                anthropic { apiKey("k3") }
            }
        }
        assertTrue("openai" in ai.registry)
        assertTrue("deepseek" in ai.registry)
        assertTrue("anthropic" in ai.registry)
        assertEquals("openai", ai.defaultProvider.name)
    }

    @Test
    fun racBuilderThrowsWhenNoProviderRegistered() {
        assertFailsWith<RACException> {
            llm { }
        }
    }

    @Test
    fun providerRegistryGetThrowsWhenNotFound() {
        val registry = ProviderRegistry()
        assertFailsWith<RACException> { registry.get("nonexistent") }
    }

    @Test
    fun providerRegistryGetOrNullReturnsNull() {
        val registry = ProviderRegistry()
        assertEquals(null, registry.getOrNull("nonexistent"))
    }

    @Test
    fun providerRegistryContains() {
        val registry = ProviderRegistry()
        registry.register(
            SimpleModelProvider(
                name = "test",
                baseUrl = "http://x",
                defaultApiType = ApiType.COMPLETIONS,
                models = mapOf("m" to ModelConfig()),
            ),
        )
        assertTrue("test" in registry)
    }

    @Test
    fun ollamaProviderLocalDefaults() {
        val ai = llm {
            providers {
                ollama { }
            }
        }
        assertEquals("ollama", ai.defaultProvider.name)
        assertNull(ai.defaultProvider.apiKey)
        assertEquals("http://localhost:11434/v1", ai.defaultProvider.baseUrl)
        assertEquals("llama3.1", ai.defaultProvider.defaultModel)
    }

    @Test
    fun anthropicProviderHasVersionHeader() {
        val ai = llm {
            providers {
                anthropic { apiKey("test") }
            }
        }
        assertEquals("2023-06-01", ai.defaultProvider.defaultHeaders["anthropic-version"])
        assertEquals(ApiType.ANTHROPIC, ai.defaultProvider.defaultApiType)
    }

    @Test
    fun allProvidersHaveCorrectDefaults() {
        val ai = llm {
            providers {
                kimi { apiKey("k") }
                glm { apiKey("k") }
                minimax { apiKey("k") }
                doubao { apiKey("k") }
                qwen { apiKey("k") }
                mimo { apiKey("k") }
                gemini { apiKey("k") }
            }
        }
        val kimi = ai.registry.get("kimi")
        assertEquals("https://api.moonshot.cn/v1", kimi.baseUrl)
        assertEquals("moonshot-v1-8k", kimi.defaultModel)

        val glm = ai.registry.get("glm")
        assertEquals("https://open.bigmodel.cn/api/paas/v4", glm.baseUrl)
        assertEquals("glm-4-flash", glm.defaultModel)

        val minimax = ai.registry.get("minimax")
        assertEquals("https://api.minimaxi.chat/v1", minimax.baseUrl)
        assertEquals("abab6.5-chat", minimax.defaultModel)

        val doubao = ai.registry.get("doubao")
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", doubao.baseUrl)
        assertEquals("doubao-seed-1-6", doubao.defaultModel)

        val qwen = ai.registry.get("qwen")
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", qwen.baseUrl)
        assertEquals("qwen-plus", qwen.defaultModel)

        val mimo = ai.registry.get("mimo")
        assertEquals("https://api.mimo.xiaomi.com/v1", mimo.baseUrl)
        assertEquals("mimo-7b", mimo.defaultModel)

        val gemini = ai.registry.get("gemini")
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", gemini.baseUrl)
        assertEquals("gemini-1.5-flash", gemini.defaultModel)
    }
}
