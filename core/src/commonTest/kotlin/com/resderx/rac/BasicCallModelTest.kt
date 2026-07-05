package com.resderx.rac

import com.resderx.rac.network.call.completions.basicSingleCompletionsApiCallModel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class BasicCallModelTest {
    @Test
    fun testBasicCallModelTest() = runTest {
        basicSingleCompletionsApiCallModel(
            urlString = "https://api.deepseek.com/chat/completions",
            token = "sk-b5a1645b2ea04c7bbbd3dfb0c3cf8590",
            body = """
                {
                      "messages": [
                        {
                          "content": "You are a helpful assistant",
                          "role": "system"
                        },
                        {
                          "content": "Hi",
                          "role": "user"
                        }
                      ],
                      "model": "deepseek-v4-pro",
                      "thinking": {
                        "type": "disabled"
                      },
                      "reasoning_effort": "high",
                      "stream": true
                    }
            """.trimIndent()
        ).collect { println(it) }
    }
}