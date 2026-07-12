package com.resderx.rac.messages

import kotlinx.serialization.Serializable

/**
 * 工具（函数）定义，用于向模型声明可用工具集。
 *
 * - 作用：声明一个可被模型调用的工具的名称、描述与参数 schema
 * - 必要性：跨供应商统一工具声明格式，OpenAI/Anthropic/DeepSeek 等都支持函数调用
 * - 设计思路：parameters 用 JSON Schema 字符串而非结构化对象，避免与 kotlinx-schema 的类型绑定，
 *   由调用方用 kotlinx-schema 生成器产出后以字符串形式注入
 * - 实现方式：`@Serializable` 不可变数据类，所有字段为 val
 * - 边缘情况：parameters 为空 schema `{}` 表示无参数工具；description 缺失时部分模型会降低调用准确率，
 *   建议调用方始终提供
 *
 * @property name 工具（函数）名称，全局唯一，模型据此发起 ToolCall
 * @property description 工具功能的人类可读描述，供模型判断何时调用
 * @property parameters 工具参数的 JSON Schema 字符串，由 kotlinx-schema 生成
 */
@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: String,
)
