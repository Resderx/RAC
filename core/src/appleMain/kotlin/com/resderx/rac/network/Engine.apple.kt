package com.resderx.rac.network

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

actual fun getEngine(): HttpClientEngineFactory<HttpClientEngineConfig> {
    return Darwin
}