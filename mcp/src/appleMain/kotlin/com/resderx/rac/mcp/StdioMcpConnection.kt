package com.resderx.rac.mcp

/**
 * Apple 平台 Stdio MCP 连接创建（暂不支持）。
 *
 * Apple (macOS/iOS) Native 平台的子进程管理需调用 POSIX API（fork/exec + 管道），
 * 当前版本暂不支持。请使用 [HttpTransport] 替代。
 */
@Throws(UnsupportedOperationException::class)
internal actual fun createStdioConnection(
    transport: StdioTransport,
    timeoutMillis: Long,
): McpConnection = throw UnsupportedOperationException(
    "StdioTransport is not yet supported on Apple Native. Use HttpTransport instead."
)
