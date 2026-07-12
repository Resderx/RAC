package com.resderx.rac.mcp

/**
 * Linux 平台 Stdio MCP 连接创建（暂不支持）。
 *
 * Linux Native 平台的子进程管理需调用 POSIX API（fork/exec + 管道），
 * 当前版本暂不支持。请使用 [HttpTransport] 替代。
 */
@Throws(UnsupportedOperationException::class)
internal actual fun createStdioConnection(
    transport: StdioTransport,
    timeoutMillis: Long,
): McpConnection = throw UnsupportedOperationException(
    "StdioTransport is not yet supported on Linux Native. Use HttpTransport instead."
)
