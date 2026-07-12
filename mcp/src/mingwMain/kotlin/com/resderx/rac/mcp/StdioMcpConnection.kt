package com.resderx.rac.mcp

/**
 * mingw 平台 Stdio MCP 连接创建（暂不支持）。
 *
 * mingw (Windows Native) 平台的子进程管理需调用 Windows API（CreateProcess + 管道），
 * 实现复杂度高，当前版本暂不支持。请使用 [HttpTransport] 替代。
 */
@Throws(UnsupportedOperationException::class)
internal actual fun createStdioConnection(
    transport: StdioTransport,
    timeoutMillis: Long,
): McpConnection = throw UnsupportedOperationException(
    "StdioTransport is not yet supported on mingw (Native Windows). Use HttpTransport instead."
)
