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

package top.resderx.rac.network.call.completions

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