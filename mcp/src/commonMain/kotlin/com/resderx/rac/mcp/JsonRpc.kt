package com.resderx.rac.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * JSON-RPC 2.0 消息模型，定义 MCP 协议的通信信封。
 *
 * - 作用：为 MCP 客户端与服务器之间的通信提供标准化的消息格式，遵循 JSON-RPC 2.0 规范
 *   （https://www.jsonrpc.org/specification）
 * - 必要性：MCP 协议基于 JSON-RPC 2.0，所有请求/响应/通知均通过此模型序列化与反序列化；
 *   集中定义避免在各方法中重复内联 JSON 构造
 * - 设计思路：将请求（有 id）、响应（有 id + result/error）、通知（无 id）分为独立数据类；
 *   params 使用 JsonElement 以支持任意结构参数（工具调用参数、资源 URI 等）；
 *   jsonrpc 为构造函数必填参数（无默认值），确保即使 encodeDefaults=false 也会序列化
 * - 实现方式：kotlinx-serialization @Serializable 数据类，JsonElement 作为灵活参数载体；
 *   jsonrpc 无默认值保证始终序列化；params 有默认值 null，encodeDefaults=false 时不序列化
 * - 可能的问题：反序列化时需配置 ignoreUnknownKeys=true，否则未知字段会导致失败
 * - 边缘情况：id 可为 Int 或 String（JSON-RPC 允许），此处统一用 Long 兼容数字 id；
 *   notification 无 id，不期望响应；error 的 data 字段可选
 * - 优点：类型安全、序列化自动、支持任意参数结构
 * - 算法/数据结构：纯数据类，无算法；序列化/反序列化为 O(n)，n 为 JSON 节点数
 * - 时间复杂度：序列化/反序列化 O(n)
 * - 空间复杂度：O(n)，取决于参数大小
 */

/**
 * JSON-RPC 2.0 请求消息。
 *
 * - 作用：客户端向服务器发送的方法调用，携带唯一 id 以匹配响应
 * - 边缘：params 可为 null（无参数方法），encodeDefaults=false 时不序列化 null params
 *
 * @property jsonrpc JSON-RPC 协议版本，固定为 "2.0"（构造函数必填，保证序列化）
 * @property id 请求标识符，用于匹配对应的响应
 * @property method 方法名（如 "initialize"、"tools/list"、"tools/call"）
 * @property params 方法参数，JSON 元素；null 表示无参数
 */
@Serializable
data class JsonRpcRequest(
    val jsonrpc: String,
    val id: Long,
    val method: String,
    val params: JsonElement? = null,
) {
    companion object {
        /** 创建标准 JSON-RPC 2.0 请求的便捷工厂方法。 */
        fun create(id: Long, method: String, params: JsonElement? = null): JsonRpcRequest =
            JsonRpcRequest(jsonrpc = "2.0", id = id, method = method, params = params)
    }
}

/**
 * JSON-RPC 2.0 通知消息（无 id，不期望响应）。
 *
 * - 作用：客户端向服务器发送的单向通知，如 "notifications/initialized"
 * - 边缘：params 可为 null
 *
 * @property jsonrpc JSON-RPC 协议版本，固定为 "2.0"
 * @property method 通知方法名
 * @property params 通知参数，JSON 元素；null 表示无参数
 */
@Serializable
data class JsonRpcNotification(
    val jsonrpc: String,
    val method: String,
    val params: JsonElement? = null,
) {
    companion object {
        /** 创建标准 JSON-RPC 2.0 通知的便捷工厂方法。 */
        fun create(method: String, params: JsonElement? = null): JsonRpcNotification =
            JsonRpcNotification(jsonrpc = "2.0", method = method, params = params)
    }
}

/**
 * JSON-RPC 2.0 响应消息。
 *
 * - 作用：服务器对请求的回复，包含 result（成功）或 error（失败），二者互斥
 * - 边缘：result 与 error 互斥——成功时 error=null，失败时 result=null；
 *   id 可为 null（解析错误时服务器无法关联请求）
 *
 * @property jsonrpc JSON-RPC 协议版本
 * @property id 对应请求的 id；解析错误时可为 null
 * @property result 成功时的结果，JSON 元素；失败时为 null
 * @property error 失败时的错误对象；成功时为 null
 */
@Serializable
data class JsonRpcResponse(
    val jsonrpc: String? = null,
    val id: Long? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
) {
    /** 判断此响应是否为错误响应。 */
    fun isError(): Boolean = error != null
}

/**
 * JSON-RPC 2.0 错误对象。
 *
 * - 作用：描述方法调用失败的原因，含错误码、消息与可选附加数据
 * - 错误码约定：-32700 解析错误、-32600 无效请求、-32601 方法不存在、
 *   -32602 无效参数、-32603 内部错误；-32000~-32099 为实现定义的服务器错误
 *
 * @property code 错误码（整数）
 * @property message 错误描述
 * @property data 附加错误数据，JSON 元素；可选
 */
@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)
