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

package top.resderx.rac.a2a

import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow

/**
 * A2A（Agent-to-Agent Protocol）客户端接口。
 *
 * - 作用：定义 Client 与远端 A2A Server 交互的协议方法，覆盖任务发送、流式订阅、
 *   查询、列出、取消等核心操作
 * - 必要性：RAC 需要原生支持 A2A Client 角色，使 RAC 能调用任何兼容 A2A 的远端 Agent
 *   （如 Google ADK、LangGraph、CrewAI 等）；本接口抽象协议细节，便于替换实现
 * - 设计思路：方法签名与 A2A v1.0 JSON-RPC 绑定对齐；流式方法返回 `Flow<A2aStreamEvent>`
 *   以支持 SSE 增量推送；非流式方法返回具体结果类型
 * - 规范来源：https://a2a-protocol.org/latest/specification/#94-core-methods
 * - 生命周期：典型流程为 `sendMessage` → 轮询 `getTask` 或 `sendStreamingMessage` 订阅 →
 *   任务终态（COMPLETED/FAILED/CANCELED/REJECTED）后结束
 */
interface A2aClient {

    /**
     * 发送消息（tasks/send）——非流式，返回 Task 或直接 Message。
     *
     * @param params 发送参数（含消息、任务 ID、配置等）
     * @return 发送结果——[top.resderx.rac.a2a.SendMessageResult.TaskResult] 表示异步任务，
     *   [top.resderx.rac.a2a.SendMessageResult.MessageResult] 表示同步直接响应
     */
    suspend fun sendMessage(params: top.resderx.rac.a2a.SendMessageParams): top.resderx.rac.a2a.SendMessageResult

    /**
     * 发送消息并订阅流式更新（tasks/sendSubscribe）——返回 SSE 事件流。
     *
     * - 流事件类型：[top.resderx.rac.a2a.TaskStatusUpdateEvent]（状态变化）、[top.resderx.rac.a2a.TaskArtifactUpdateEvent]（产出物块）、
     *   [top.resderx.rac.a2a.SendMessageResult]（初始 Task 或 Message）
     * - 流终止条件：收到 `final=true` 的状态更新事件
     *
     * @param params 发送参数
     * @return 流式事件流
     */
    fun sendStreamingMessage(params: top.resderx.rac.a2a.SendStreamingMessageParams): Flow<top.resderx.rac.a2a.A2aStreamEvent>

    /**
     * 查询任务当前状态（tasks/get）。
     *
     * @param params 查询参数（含任务 ID 与可选 historyLength）
     * @return 任务当前完整状态
     */
    suspend fun getTask(params: top.resderx.rac.a2a.GetTaskParams): top.resderx.rac.a2a.GetTaskResult
    suspend fun listTasks(params: top.resderx.rac.a2a.ListTasksParams = top.resderx.rac.a2a.ListTasksParams()): top.resderx.rac.a2a.ListTasksResult

    /**
     * 取消任务（tasks/cancel）。
     *
     * @param params 取消参数（含任务 ID）
     * @return 取消后的任务状态
     */
    suspend fun cancelTask(params: top.resderx.rac.a2a.CancelTaskParams): top.resderx.rac.a2a.CancelTaskResult

    /**
     * 获取 Agent Card——发现远端 Agent 的能力与端点信息。
     *
     * @param agentCardUrl Agent Card 的 URL（通常为 `/.well-known/agent.json`）；为空则使用配置默认值
     * @return Agent Card 文档
     */
    suspend fun getAgentCard(agentCardUrl: String? = null): top.resderx.rac.a2a.AgentCard

    /**
     * 关闭客户端，释放底层 HttpClient 等资源。
     *
     * - 幂等：多次调用不报错
     */
    suspend fun close()
}

/**
 * A2A 流式事件密封接口——`sendStreamingMessage` 返回的事件类型。
 *
 * - [A2aStreamEvent.Initial]：流的首条事件，包含 Task 或 Message
 * - [A2aStreamEvent.StatusUpdate]：任务状态变化
 * - [A2aStreamEvent.ArtifactUpdate]：产出物块推送
 */
sealed interface A2aStreamEvent {
    /**
     * 流的初始事件，携带 Task 或 Message。
     *
     * @property result 初始结果
     */
    data class Initial(val result: top.resderx.rac.a2a.SendMessageResult) : A2aStreamEvent

    /**
     * 任务状态更新事件。
     *
     * @property event 状态更新
     */
    data class StatusUpdate(val event: top.resderx.rac.a2a.TaskStatusUpdateEvent) : A2aStreamEvent

    /**
     * 任务产出物更新事件。
     *
     * @property event 产出物更新
     */
    data class ArtifactUpdate(val event: top.resderx.rac.a2a.TaskArtifactUpdateEvent) : A2aStreamEvent
}

/**
 * A2A 客户端配置。
 *
 * @property baseUrl 远端 A2A Server 的基础 URL（如 `https://agent.example.com`）
 * @property httpClient Ktor HttpClient 实例；若为 null 则由客户端自建（使用平台默认引擎）
 * @property ownHttpClient 客户端是否拥有 HttpClient 生命周期（true 时 close 会关闭它）
 * @property apiKey API Key 鉴权值，可空（无鉴权时为 null）
 * @property apiKeyHeaderName API Key 的 header 名称，默认 "X-API-Key"
 * @property acceptedOutputModes Client 接受的输出 MIME 类型，默认 ["text/plain"]
 * @property jsonRpcVersion JSON-RPC 协议版本字符串，默认 "2.0"
 * @property a2aVersion A2A 协议版本字符串，默认 "1.0.0"（用于 A2A-Version header）
 */
data class A2aClientConfig(
    val baseUrl: String,
    val httpClient: HttpClient? = null,
    val ownHttpClient: Boolean = true,
    val apiKey: String? = null,
    val apiKeyHeaderName: String = "X-API-Key",
    val acceptedOutputModes: List<String> = listOf("text/plain"),
    val jsonRpcVersion: String = "2.0",
    val a2aVersion: String = "1.0.0",
)

/**
 * 创建 A2A 客户端实例的工厂函数。
 *
 * - 作用：隐藏 [top.resderx.rac.a2a.DefaultA2aClient] 实现细节，仅暴露 [top.resderx.rac.a2a.A2aClient] 接口
 * - 用法：`val client = A2aClient(config)`
 *
 * @param config 客户端配置
 * @return A2A 客户端实例
 */
fun A2aClient(config: top.resderx.rac.a2a.A2aClientConfig): top.resderx.rac.a2a.A2aClient =
    top.resderx.rac.a2a.DefaultA2aClient(config)
