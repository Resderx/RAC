package com.resderx.rac.mcp

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
