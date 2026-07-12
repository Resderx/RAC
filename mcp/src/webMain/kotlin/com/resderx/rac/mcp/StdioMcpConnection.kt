package com.resderx.rac.mcp

/**
 * Web 平台（JS/Wasm）Stdio MCP 连接创建（不支持）。
 *
 * Web 平台无法启动子进程，Stdio 传输不可用。请使用 [HttpTransport] 替代。
 */
@Throws(UnsupportedOperationException::class)
internal actual fun createStdioConnection(
    transport: StdioTransport,
    timeoutMillis: Long,
): McpConnection = throw UnsupportedOperationException(
    "StdioTransport is not supported on Web (JS/Wasm). Use HttpTransport instead."
)
