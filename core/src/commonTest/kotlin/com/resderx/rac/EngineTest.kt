package com.resderx.rac

import com.resderx.rac.network.getEngine
import io.ktor.client.HttpClient
import kotlin.test.Test

class EngineTest {
    @Test
    fun testEngine() {
        println(HttpClient(getEngine()).engine::class.simpleName)
    }
}