package com.resderx.rac

import com.resderx.rac.network.getEngine
import io.ktor.client.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SSETest {
    @Test
    fun testSSE() = runTest {
        val client = HttpClient(getEngine()) {
            install(SSE)
        }

        client.sse(
            urlString = "https://api.deepseek.com/chat/completions",
            request = {
                method = HttpMethod.Post
                header("Content-Type", "application/json")
                header("Accept", "application/json")
                header("Authorization", "Bearer sk-b5a1645b2ea04c7bbbd3dfb0c3cf8590")
                setBody("""
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
                """.trimIndent())
            }
        ){
            incoming.collect {
                println(it.data)
            }
        }
    }
}