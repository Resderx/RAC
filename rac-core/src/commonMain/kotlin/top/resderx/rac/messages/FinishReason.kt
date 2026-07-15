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

package top.resderx.rac.messages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 模型生成结束原因枚举。
 *
 * - 作用：标识一次模型响应为何停止（自然结束/达长度上限/工具调用/内容过滤/未知）
 * - 必要性：跨供应商统一结束语义，Agent 流程据此决定是否继续工具调用循环
 * - 设计思路：用枚举而非字符串，编译期类型安全；序列化值与 OpenAI 协议对齐
 * - 实现方式：`@Serializable` + `@SerialName` 让序列化值与 OpenAI 协议一致
 * - 边缘情况：供应商返回未识别原因时由 API 客户端映射为 UNKNOWN；流式场景下 finishReason 仅在
 *   最后一个 chunk 出现，客户端需聚合
 */
@Serializable
enum class FinishReason {
    @SerialName("stop") STOP,
    @SerialName("length") LENGTH,
    @SerialName("tool_calls") TOOL_CALLS,
    @SerialName("content_filter") CONTENT_FILTER,
    @SerialName("unknown") UNKNOWN,
}
