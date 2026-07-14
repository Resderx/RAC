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

import top.resderx.rac.dsl.Llm
import top.resderx.rac.messages.AIMessage
import top.resderx.rac.messages.FinishReason

/**
 * 通过 A2A 协议调用远端 Agent，将响应归一化为 [top.resderx.rac.messages.AIMessage]（Llm 扩展函数）。
 *
 * - 作用：作为 A2A Client 调用远端 Agent（Google ADK、LangGraph、CrewAI 等），
 *   发送文本提示，收集流式更新，返回统一的 AIMessage
 * - 必要性：Llm 需支持 A2A Client 角色，使 Llm 能与任何 A2A 兼容的远端 Agent 通信；
 *   与 [chatWithAcpAgent] 对称——ACP 用于本地 stdio Agent，A2A 用于 HTTP 远端 Agent
 * - 模块拆分：本扩展函数位于 rac-a2a 模块，避免 core 模块依赖 A2A 协议包；
 *   调用方需在依赖中同时引入 rac-core 与 rac-a2a
 * - 设计思路：
 *   1. 构造 SendStreamingMessageParams（含 user 角色的 TextPart）
 *   2. 调用 [top.resderx.rac.a2a.A2aClient.sendStreamingMessage] 发起流式请求
 *   3. 从流式事件中提取 agent 消息文本并累积
 *   4. 从最终 Task 状态映射 FinishReason
 *   5. 返回统一的 AIMessage
 * - 边缘：
 *   - 非文本 Part（FilePart/DataPart）被忽略
 *   - 流式事件中的 ArtifactUpdate 提取文本累积到 content
 *   - Task 终态映射：COMPLETED→STOP、INPUT_REQUIRED→TOOL_CALLS、FAILED→UNKNOWN
 *
 * @receiver Llm 实例（仅作为命名空间锚点，不直接使用其内部能力）
 * @param client A2A 客户端实例（调用方管理生命周期）
 * @param prompt 文本提示
 * @param onUpdate 流式更新回调（转发给调用方）
 * @return 统一的 AIMessage（content 为累积的 Agent 响应文本）
 * @throws top.resderx.rac.exceptions.RACException 当 A2A 请求失败时向上传播
 */
suspend fun top.resderx.rac.dsl.Llm.chatWithA2aAgent(
    client: top.resderx.rac.a2a.A2aClient,
    prompt: String,
    onUpdate: suspend (top.resderx.rac.a2a.A2aStreamEvent) -> Unit = {},
): top.resderx.rac.messages.AIMessage {
    // 1. 构造发送参数——user 角色的 TextPart
    val params = SendStreamingMessageParams(
        message = top.resderx.rac.a2a.Message(
            role = top.resderx.rac.a2a.Role.USER,
            parts = listOf(top.resderx.rac.a2a.TextPart(text = prompt)),
        ),
    )

    // 2. 累积 Agent 响应文本
    val contentBuilder = StringBuilder()
    var finalTaskState: top.resderx.rac.a2a.TaskState = top.resderx.rac.a2a.TaskState.COMPLETED

    // 3. 发送流式请求并收集更新
    client.sendStreamingMessage(params).collect { event ->
        onUpdate(event)
        when (event) {
            is top.resderx.rac.a2a.A2aStreamEvent.Initial -> {
                // 初始事件——若为 Task，提取其状态
                val result = event.result
                if (result is top.resderx.rac.a2a.SendMessageResult.TaskResult) {
                    finalTaskState = result.task.status.state
                }
            }
            is top.resderx.rac.a2a.A2aStreamEvent.StatusUpdate -> {
                // 状态更新——记录最终状态
                finalTaskState = event.event.status.state
                // 若 task history 含 agent 消息，提取文本
            }
            is top.resderx.rac.a2a.A2aStreamEvent.ArtifactUpdate -> {
                // 产出物更新——提取文本 Part 累积
                event.event.artifact.parts
                    .filterIsInstance<top.resderx.rac.a2a.TextPart>()
                    .forEach { contentBuilder.append(it.text) }
            }
        }
    }

    // 4. 映射 TaskState 到 FinishReason 并返回
    return top.resderx.rac.messages.AIMessage(
        content = contentBuilder.toString(),
        finishReason = finalTaskState.toFinishReason(),
    )
}

/**
 * 将 Llm 作为 A2A Agent Server 启动，返回协议无关的 JSON-RPC 分发器（Llm 扩展函数）。
 *
 * - 作用：创建 [top.resderx.rac.a2a.RacA2aAgent]（将 Llm 的 AI 调用能力适配为 A2A Agent）并包装进 [top.resderx.rac.a2a.A2aAgentServer]，
 *   使任何 A2A 兼容的 Client 都能通过 A2A 协议调用 Llm 管理的 AI 供应商
 * - 必要性：Llm 需支持 A2A Agent 角色（Server 端），与 Client 角色（[top.resderx.rac.a2a.chatWithA2aAgent]）对称；
 *   本方法封装 RacA2aAgent + A2aAgentServer 的创建，调用方只需配置 Agent Card 与系统提示
 * - 模块拆分：本扩展函数位于 rac-a2a 模块，避免 core 模块依赖 A2A 协议包
 * - 设计思路：
 *   1. 构造 [top.resderx.rac.a2a.AgentCard]：描述 Agent 身份、能力、端点
 *   2. 构造 [top.resderx.rac.a2a.RacA2aAgent]：以当前 Llm 实例为 AI 引擎
 *   3. 构造 [top.resderx.rac.a2a.A2aAgentServer]：协议无关的 JSON-RPC 分发器
 *   4. 返回 A2aAgentServer（调用方需自行绑定 HTTP 服务器）
 * - 与 [serveAsAcpAgent] 的差异：
 *   - ACP 使用 stdio 传输，serveAsAcpAgent 内部创建 stdio 连接
 *   - A2A 使用 HTTP 传输，serveAsA2aAgent 返回协议无关分发器，HTTP 绑定由调用方完成
 *   - 原因：KMP 库不引入 HTTP 服务器依赖，保持跨平台兼容
 * - 边缘：systemPrompt 为 null 时不注入系统消息
 *
 * @receiver Llm 实例，作为 Agent 的 AI 引擎
 * @param agentCard Agent Card 元数据，默认 name="rac-agent"、url 为空（调用方填充）
 * @param systemPrompt 系统提示词，每次 chat 调用时注入；null 表示不注入
 * @return A2A Agent Server（协议无关分发器，调用方需绑定 HTTP 服务器）
 */
fun top.resderx.rac.dsl.Llm.serveAsA2aAgent(
    agentCard: top.resderx.rac.a2a.AgentCard = top.resderx.rac.a2a.AgentCard(
        name = "rac-agent",
        description = "LLM Agent — Kotlin Multiplatform AI Call Library",
        url = "",
        provider = top.resderx.rac.a2a.AgentProvider(organization = "ResDerX"),
    ),
    systemPrompt: String? = null,
): top.resderx.rac.a2a.A2aAgentServer {
    val handler = top.resderx.rac.a2a.RacA2aAgent(
        llm = this,
        agentCard = agentCard,
        systemPrompt = systemPrompt,
    )
    return top.resderx.rac.a2a.A2aAgentServer(handler)
}

/**
 * A2A TaskState 到 RAC FinishReason 的映射（文件私有）。
 *
 * - COMPLETED → STOP（正常完成）
 * - INPUT_REQUIRED → TOOL_CALLS（需用户提供输入，最接近 TOOL_CALLS 语义）
 * - FAILED → UNKNOWN（执行失败）
 * - CANCELED → STOP（用户取消，FinishReason 无对应值）
 * - REJECTED → CONTENT_FILTER（请求被拒绝）
 * - AUTH_REQUIRED → UNKNOWN（需认证）
 * - WORKING → UNKNOWN（仍在进行中，不应出现在终态）
 */
private fun top.resderx.rac.a2a.TaskState.toFinishReason(): top.resderx.rac.messages.FinishReason = when (this) {
    top.resderx.rac.a2a.TaskState.COMPLETED -> top.resderx.rac.messages.FinishReason.STOP
    top.resderx.rac.a2a.TaskState.INPUT_REQUIRED -> top.resderx.rac.messages.FinishReason.TOOL_CALLS
    top.resderx.rac.a2a.TaskState.FAILED -> top.resderx.rac.messages.FinishReason.UNKNOWN
    top.resderx.rac.a2a.TaskState.CANCELED -> top.resderx.rac.messages.FinishReason.STOP
    top.resderx.rac.a2a.TaskState.REJECTED -> top.resderx.rac.messages.FinishReason.CONTENT_FILTER
    top.resderx.rac.a2a.TaskState.AUTH_REQUIRED -> top.resderx.rac.messages.FinishReason.UNKNOWN
    top.resderx.rac.a2a.TaskState.WORKING -> top.resderx.rac.messages.FinishReason.UNKNOWN
}
