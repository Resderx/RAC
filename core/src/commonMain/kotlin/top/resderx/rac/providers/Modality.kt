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

package top.resderx.rac.providers

/**
 * 模型支持的输入模态枚举。
 *
 * - 作用：声明模型可接收的内容模态类型，供 [ModelConfig.modalities] 字段标记每个模型的多模态能力，
 *   使调用方在构造消息时能根据模型能力选择合适的 [top.resderx.rac.messages.Content] 子类
 * - 必要性：多模态支持需要知道当前模型是否接受图像/音频输入；不声明能力则调用方可能在向纯文本模型
 *   发送 [top.resderx.rac.messages.ImageContent] 时收到 400 错误；显式声明能力可在客户端层提前校验
 *   或在文档/IDE 补全中引导正确用法
 * - 设计思路：密封枚举限定三种已知模态——文本/图像/音频；与 [top.resderx.rac.messages.Content]
 *   的三个子类（[top.resderx.rac.messages.TextContent]/
 *   [top.resderx.rac.messages.ImageContent]/[top.resderx.rac.messages.AudioContent]）一一对应
 * - 实现方式：普通 enum class，无序列化需求（仅运行时配置用，不入请求体）
 * - 边缘情况：[TEXT] 是所有模型的隐式默认能力；preset 中即使纯文本模型也建议显式标 [TEXT]
 *   以便调用方统一判断；未来扩展新模态（如 video）时新增枚举项即可
 */
enum class Modality {
    /** 文本模态——所有模型隐式支持，对应 [top.resderx.rac.messages.TextContent]。 */
    TEXT,

    /** 图像模态——视觉模型支持，对应 [top.resderx.rac.messages.ImageContent]。 */
    IMAGE,

    /** 音频模态——语音模型支持，对应 [top.resderx.rac.messages.AudioContent]。 */
    AUDIO,
}
