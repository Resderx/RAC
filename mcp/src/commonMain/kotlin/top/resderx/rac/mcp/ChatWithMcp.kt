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

package top.resderx.rac.mcp

import top.resderx.rac.dsl.ChatRequestBuilder
import top.resderx.rac.dsl.Llm
import top.resderx.rac.messages.AIMessage

/**
 * 带 MCP 工具自动注册与多轮调用的非流式 Chat 调用（Llm 扩展函数）。
 *
 * - 作用：自动从 [McpClient] 发现工具并注入到对话，复用 [Llm.chatWithTools] 的多轮工具调用循环，
 *   使调用方无需手动声明 MCP 工具集——只需提供已连接的 MCP 客户端即可获得完整的工具调用能力
 * - 必要性：MCP 工具由服务器端动态发现（通过 `tools/list` JSON-RPC 请求），无法在编译期静态声明；
 *   本方法封装「发现工具 → 注入 tools → 循环执行 callTool」的完整流程
 * - 模块拆分：本扩展函数位于 rac-mcp 模块，避免 core 模块依赖 MCP 协议包；
 *   调用方需在依赖中同时引入 rac-core 与 rac-mcp
 * - 设计思路：
 *   1. 调用 [mcpClient.listTools] 获取 MCP 服务器提供的工具定义（suspend）
 *   2. 委托 [Llm.chatWithTools]，在 builder 块内先执行调用方的 [block]（用户消息/参数），
 *      再通过 [ChatRequestBuilder.addTools] 注入 MCP 工具（与用户手动声明的 tools 合并）
 *   3. toolExecutor 委托 [mcpClient.callTool]，按 ToolCall.name 路由到对应 MCP 工具
 * - 实现方式：suspend 扩展函数，先 suspend 获取工具列表（因 DSL 块非 suspend，无法在块内调用 listTools），
 *   再将工具作为闭包变量传入 [Llm.chatWithTools] 的块内调用 addTools
 * - 可能的问题：[mcpClient.listTools] 失败（MCP 服务器未连接）会直接抛异常；
 *   MCP 工具名与用户手动声明的 tools 重名时，模型可能调用任一，行为由模型决定
 * - 边缘情况：MCP 服务器无工具时 listTools 返回空列表，addTools 不修改 builder（等效于普通 chat）；
 *   调用方在 block 内也可通过 tools { } 声明本地工具，与 MCP 工具共存
 * - 优点：调用方零样板代码即可获得 MCP 工具调用能力；与 [Llm.chatWithTools] 共享循环逻辑，行为一致
 * - 算法/数据结构：委托 [Llm.chatWithTools]，无额外算法
 * - 时间复杂度：O(L + R * (T + N))，L 为 listTools 耗时，R 为循环轮数，T 为每轮工具数，N 为响应解析
 * - 空间复杂度：同 [Llm.chatWithTools]，额外 O(K) 存放 MCP 工具列表
 *
 * @receiver Llm 实例，提供 chatWithTools 能力
 * @param mcpClient 已连接的 MCP 客户端，提供工具发现与调用能力
 * @param maxRounds 最大工具调用循环轮数，默认 10；透传给 [Llm.chatWithTools]
 * @param block 在 ChatRequestBuilder 作用域内构建初始消息与可选的本地工具定义
 * @return 最终的 AIMessage（无工具调用或达到 maxRounds 上限）
 * @throws top.resderx.rac.exceptions.RACException 当 MCP 工具发现/调用失败或模型调用失败时向上传播
 */
suspend fun Llm.chatWithMcp(
    mcpClient: McpClient,
    maxRounds: Int = 10,
    block: ChatRequestBuilder.() -> Unit,
): AIMessage {
    // 先 suspend 获取 MCP 工具列表（DSL 块非 suspend，无法在块内调用）
    val mcpTools = mcpClient.listTools()
    // 委托 chatWithTools，在块内先执行用户的 block，再注入 MCP 工具
    return chatWithTools(
        maxRounds = maxRounds,
        toolExecutor = { toolCall -> mcpClient.callTool(toolCall.name, toolCall.arguments) },
    ) {
        block()
        addTools(mcpTools)
    }
}
