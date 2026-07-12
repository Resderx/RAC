package com.resderx.rac.acp

/**
 * ACP 服务端 stdio 传输连接的平台 stub 实现（Web 平台暂不支持）。
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
