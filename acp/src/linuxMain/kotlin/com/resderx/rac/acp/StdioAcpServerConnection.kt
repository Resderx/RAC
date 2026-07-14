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

package com.resderx.rac.acp

/**
 * ACP 服务端 stdio 传输连接的平台 stub 实现（Linux 平台暂不支持）。
 *
 * - 作用：提供 [createAcpStdioServerConnection] 的 actual 实现，当前平台抛 [UnsupportedOperationException]
 * - 必要性：Kotlin Multiplatform 的 expect/actual 机制要求每个目标平台提供 actual 实现
 *
 * @return 服务端 ACP 连接实例（当前平台始终抛异常）
 * @throws UnsupportedOperationException 当前平台不支持
 */
@Throws(UnsupportedOperationException::class)
internal actual fun createAcpStdioServerConnection(): AcpConnection = throw UnsupportedOperationException(
    "ACP server stdio transport is not supported on this platform. Use HTTP transport instead, or run on JVM."
)
