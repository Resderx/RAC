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

/**
 * Android 平台 Stdio MCP 连接创建（暂不支持）。
 *
 * Android 虽基于 JVM 支持 ProcessBuilder，但 androidMain 源集与 jvmMain 独立，
 * 无法共享 JvmStdioMcpConnection 实现。后续可添加 jvmCommon 中间源集复用代码。
 * 当前请使用 [HttpTransport] 替代。
 */
@Throws(UnsupportedOperationException::class)
internal actual fun createStdioConnection(
    transport: StdioTransport,
    timeoutMillis: Long,
): McpConnection = throw UnsupportedOperationException(
    "StdioTransport is not yet supported on Android. Use HttpTransport instead."
)
