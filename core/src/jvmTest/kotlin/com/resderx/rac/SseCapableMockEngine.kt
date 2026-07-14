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

import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.sse.SSECapability
import io.ktor.client.plugins.websocket.WebSocketCapability
import io.ktor.client.plugins.websocket.WebSocketExtensionsCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.ResponseAdapterAttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI

/**
 * MockEngine 子类，额外声明支持 SSECapability 并在 execute 中应用 ResponseAdapter。
 *
 * - 作用：Ktor 3.x 的 SSE 插件要求引擎声明 SSECapability，否则抛 IllegalArgumentException；
 *   原生 MockEngine 仅支持 HttpTimeout/WebSocket 能力，本类补充 SSECapability 使 SSE 测试可行。
 *   此外，原生 MockEngine 的 execute 不会调用 ResponseAdapterAttributeKey 对应的适配器，
 *   导致 SSE 插件的 Transform 阶段收到 ByteReadChannel 而非 SSESession，抛
 *   "Expected SSESession content" 异常。本类重写 execute，在 super.execute 返回后
 *   检查请求是否携带 ResponseAdapterAttributeKey，若存在且响应体为 ByteReadChannel，
 *   则调用 adapter.adapt 将其转换为 SSESession（DefaultClientSSESession），与 OkHttp/CIO
 *   等真实引擎的行为对齐
 * - 必要性：所有 SSE 相关的 JVM 测试（SSEClient/CompletionsClient.stream/RAC.chatStream）均需此引擎
 * - 设计思路：镜像 OkHttpEngine.buildResponseData 的逻辑——从 data.attributes 读取
 *   ResponseAdapterAttributeKey，对 ByteReadChannel 响应体调用 adapt()，若返回非 null
 *   则用适配后的 body 构造新的 HttpResponseData
 * - 边缘情况：adapter.adapt 返回 null 时（如非 SSE 请求、非 2xx 状态码、Content-Type 不匹配），
 *   保留原始响应；非 ByteReadChannel 的响应体也直接透传
 * - 算法：O(1) 属性查找 + O(1) 适配调用
 * - 时间复杂度：O(1)
 * - 空间复杂度：O(1)（仅在适配成功时创建一个 DefaultClientSSESession 对象）
 *
 * @param handler Mock 请求处理器，决定响应内容
 */
@OptIn(InternalAPI::class)
class SseCapableMockEngine(
    handler: MockRequestHandler,
) : MockEngine(MockEngineConfig().apply { requestHandlers.add(handler) }) {

    /** 声明引擎支持的能力集，含 SSECapability 以通过 SSE 插件的能力检查。 */
    override val supportedCapabilities: Set<HttpClientEngineCapability<out Any>> = setOf(
        HttpTimeoutCapability,
        WebSocketCapability,
        WebSocketExtensionsCapability,
        SSECapability,
    )

    /**
     * 重写 execute，在 MockEngine 原始执行后应用 ResponseAdapter。
     *
     * - 作用：SSE 插件在 AfterRender 阶段将 data.attributes[ResponseAdapterAttributeKey]
     *   设为 SSEClientResponseAdapter，真实引擎（OkHttp/CIO）会在 execute 中调用
     *   adapter.adapt() 将 ByteReadChannel 包装为 DefaultClientSSESession；
     *   MockEngine 缺少此步骤，本方法补齐
     * - 实现方式：调用 super.execute 获取原始 HttpResponseData，检查 adapter 是否存在、
     *   body 是否为 ByteReadChannel，调用 adapt()，若返回非 null 则构造新 HttpResponseData
     * - 边缘情况：adapt 返回 null（非 SSE 请求 / 非 2xx / Content-Type 不匹配）时透传原始响应
     *
     * @param data HTTP 请求数据
     * @return HTTP 响应数据，SSE 请求的 body 被替换为 SSESession
     */
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        // 先由 MockEngine 执行 handler 生成原始响应
        val response = super.execute(data)

        // 读取 SSE 插件在 AfterRender 阶段注入的 ResponseAdapter，不存在则直接返回
        val adapter = data.attributes.getOrNull(ResponseAdapterAttributeKey) ?: return response

        // 仅对 ByteReadChannel 类型的响应体进行适配（SSE 响应体即为此类型）
        val body = response.body
        if (body !is ByteReadChannel) return response

        // 调用适配器将 ByteReadChannel + SSEClientContent 转换为 DefaultClientSSESession；
        // adapt 返回 null 表示不满足 SSE 条件（如非 2xx 状态码），此时透传原始响应
        val adapted = adapter.adapt(
            data = data,
            status = response.statusCode,
            headers = response.headers,
            responseBody = body,
            outgoingContent = data.body,
            callContext = response.callContext,
        ) ?: return response

        // 用适配后的 body（SSESession）构造新的 HttpResponseData
        return HttpResponseData(
            statusCode = response.statusCode,
            requestTime = response.requestTime,
            headers = response.headers,
            version = response.version,
            body = adapted,
            callContext = response.callContext,
        )
    }
}
