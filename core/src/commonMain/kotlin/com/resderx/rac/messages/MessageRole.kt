package com.resderx.rac.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 消息角色枚举。
 *
 * - 作用：标识对话消息的发送方角色（系统/用户/助手/工具）
 * - 必要性：所有大模型 API 都用 role 区分 system/user/assistant/tool，是消息流编排的基础语义
 * - 设计思路：用枚举而非字符串，编译期类型安全，避免拼写错误；序列化值与 OpenAI 协议保持一致以降低映射成本
 * - 实现方式：`@Serializable` + `@SerialName` 让序列化值与 OpenAI 协议对齐（system/user/assistant/tool）
 * - 边缘情况：未知角色反序列化时由 `Json{ignoreUnknownKeys=true}` 处理；但枚举未知值本身会抛异常，
 *   因此 API 客户端解析各家响应时先用 String 容器接收 role，再映射为本枚举，避免直接反序列化失败
 */
@Serializable
enum class MessageRole {
    @SerialName("system") SYSTEM,
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("tool") TOOL,
}
