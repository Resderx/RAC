package com.resderx.rac.messages

import kotlinx.serialization.Serializable

/**
 * 模型发起的一次工具调用。
 *
 * - 作用：描述助手消息中模型请求执行的工具调用（函数名 + 参数）
 * - 必要性：工具调用是 Agent 流程的核心数据结构，跨供应商统一表达
 * - 设计思路：arguments 用 JSON 字符串而非结构化对象，避免与具体工具参数 schema 耦合，由调用方自行反序列化
 * - 实现方式：`@Serializable` 不可变数据类，所有字段为 val
 * - 边缘情况：arguments 可能为空字符串 `{}` 或非法 JSON，由工具执行方负责校验；id 在流式场景下可能分片到达，
 *   由 API 客户端负责聚合后再产出本对象
 *
 * @property id 工具调用唯一标识，由模型生成，用于关联后续的 ToolMessage 回执
 * @property name 要调用的工具（函数）名称，需与 ToolDefinition.name 匹配
 * @property arguments 工具参数的 JSON 字符串，由调用方按工具 schema 反序列化
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)
