package com.resderx.rac.network.call.completions

import com.resderx.rac.network.getEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

fun basicSingleCompletionsApiCallModel(
    urlString: String,
    token: String,
    body: String,
): Flow<String> = flow {
    val client = HttpClient(getEngine()) {
        install(SSE)
    }
    client.sse(
        urlString = urlString,
        request = {
            method = HttpMethod.Post
            header("Content-Type", "application/json")
            header("Accept", "application/json")
            header("Authorization", "Bearer $token")
            setBody(body)
        }
    ){
        incoming.collect {
            emit(it.data!!)
        }
    }
    client.close()
}