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

package top.resderx.rac.exceptions

/**
 * RAC 库所有异常的基类。
 *
 * - 作用：统一 RAC 库抛出的所有异常根类型，便于调用方用单个 catch 捕获
 * - 必要性：跨供应商调用可能产生网络/序列化/API/超时等多种错误，需要统一根类型以便分类处理
 * - 设计思路：继承 `Exception`，保留 message 与 cause 双信息通道；定义为 open 允许子类化扩展
 * - 实现方式：open class，主构造接收 message 与可选 cause，委托给 Exception
 * - 可能的问题：异常链较深时 cause 嵌套，调用方需遍历 getCause
 * - 边缘情况：cause 可为 null（无底层异常时）；message 可为空字符串
 * - 优点：调用方可按 `catch (e: RACException)` 统一兜底，也可按子类型精细处理
 * - 数据结构：薄包装类，无额外字段
 * - 时间复杂度：构造 O(1)
 * - 空间复杂度：O(message + cause)
 *
 * @param message 异常描述信息
 * @param cause 底层异常，可空
 */
open class RACException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 网络异常，表示请求在传输层失败。
 *
 * - 作用：标识连接失败、DNS 解析失败、TLS 握手失败等网络层错误
 * - 必要性：区分网络层错误与 API 层错误，调用方可据此实现重试或切换网络
 * - 设计思路：继承 RACException，携带原始 cause（通常是 ktor 的网络异常）
 * - 实现方式：class，主构造接收 message 与可选 cause，委托给 RACException
 * - 边缘情况：cause 可为 null；移动平台网络切换时可能瞬时触发，调用方可重试
 *
 * @param message 异常描述信息
 * @param cause 底层网络异常，可空
 */
class RACNetworkException(message: String, cause: Throwable? = null) : RACException(message, cause)

/**
 * API 异常，表示供应商返回了非成功 HTTP 状态码。
 *
 * - 作用：标识供应商 API 返回 4xx/5xx 错误，携带状态码、响应体与响应头供调用方诊断与重试
 * - 必要性：区分业务级 API 错误（如鉴权失败、限流、内容审核），调用方据 statusCode 决定重试策略；
 *   响应头中的 Retry-After 字段（429/503 场景）用于指导重试间隔
 * - 设计思路：暴露 statusCode/errorBody/headers 为公开属性，message 默认按状态码与响应体拼接；
 *   headers 默认为空 Map，兼容不携带响应头的构造场景
 * - 实现方式：class，主构造接收 statusCode/errorBody/可选 headers/可选 message，委托给 RACException
 * - 边缘情况：429 限流可重试，Retry-After 头指导等待时间；401 鉴权失败不应重试；
 *   errorBody 可能是非 JSON 文本需调用方容错解析；headers 可能为空 Map
 *
 * @property statusCode HTTP 状态码
 * @property errorBody 供应商返回的原始错误响应体
 * @property headers 响应头 Map，含 Retry-After 等重试指导字段，默认空 Map
 * @param message 异常描述信息，默认按状态码与响应体拼接
 */
class RACApiException(
    val statusCode: Int,
    val errorBody: String,
    val headers: Map<String, String> = emptyMap(),
    message: String = "API error $statusCode: $errorBody",
) : RACException(message)

/**
 * 序列化异常，表示 JSON 与领域模型互转失败。
 *
 * - 作用：标识 JSON 解析/生成失败、字段缺失、类型不匹配等序列化错误
 * - 必要性：区分序列化层错误，便于调用方定位是数据格式问题而非网络/API 问题
 * - 设计思路：携带原始 cause（通常是 kotlinx.serialization 的 SerializationException）
 * - 实现方式：class，主构造接收 message 与可选 cause，委托给 RACException
 * - 边缘情况：供应商响应字段变更可能导致解析失败，cause 保留原始序列化异常信息便于排查
 *
 * @param message 异常描述信息
 * @param cause 底层序列化异常，可空
 */
class RACSerializationException(message: String, cause: Throwable? = null) : RACException(message, cause)

/**
 * 超时异常，表示请求在规定时间内未完成。
 *
 * - 作用：标识请求超时（连接超时/读取超时），调用方可据此重试或提示用户
 * - 必要性：区分超时与其他网络错误，超时通常可安全重试
 * - 设计思路：提供默认 message，避免调用方每次构造时填写
 * - 实现方式：class，主构造接收可选 message（默认 "Request timed out"）与可选 cause，委托给 RACException
 * - 边缘情况：流式场景下首字节超时与整体超时语义不同，由调用方在抛出前区分
 *
 * @param message 异常描述信息，默认 "Request timed out"
 * @param cause 底层超时异常，可空
 */
class RACTimeoutException(message: String = "Request timed out", cause: Throwable? = null) : RACException(message, cause)
