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

package com.resderx.rac.mcp

import com.resderx.rac.exceptions.RACException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

/**
 * Stdio 传输层连接的 JVM 实现，通过 [ProcessBuilder] 启动子进程并经 stdin/stdout 交换 JSON-RPC 消息。
 *
 * - 作用：在 JVM 平台上实现 MCP Stdio 传输，与本地 MCP 服务器（Python/Node 脚本等）通信
 * - 必要性：Stdio 是 MCP 协议的主要传输方式之一（本地工具服务器），JVM 平台需完整支持
 * - 设计思路：
 *   1. connect()：ProcessBuilder 启动子进程，获取 stdin/stdout 流
 *   2. 后台读线程：持续从 stdout 读取行，放入 Channel 供 request() 消费
 *   3. request()：写入 JSON-RPC 到 stdin，从 Channel 读取直到获得响应（跳过通知）
 *   4. notify()：仅写入 JSON-RPC 到 stdin，不读取
 *   5. close()：关闭流并销毁子进程
 * - 线程安全：writeMutex 保护 stdin 写入不交错；Channel 线程安全
 * - 超时：withTimeout 控制请求超时，超时后协程取消（但阻塞的 Channel.receive 会被取消）
 * - 边缘：子进程意外退出时 Channel 关闭，request() 抛 RACException；
 *   close() 幂等，重复调用安全
 * - 时间复杂度：单次请求 O(n) 序列化 + O(1) Channel 操作 + I/O
 * - 空间复杂度：O(n) Channel 缓冲（n 为未消费的消息数）
 */
@Throws(UnsupportedOperationException::class)
internal actual fun createStdioConnection(
    transport: StdioTransport,
    timeoutMillis: Long,
): McpConnection = JvmStdioMcpConnection(transport, timeoutMillis)

/**
 * JVM 平台 Stdio MCP 连接实现。
 *
 * @property transport Stdio 传输配置
 * @property timeoutMillis 请求超时毫秒数
 */
private class JvmStdioMcpConnection(
    private val transport: StdioTransport,
    private val timeoutMillis: Long,
) : McpConnection {

    /** JSON 解析器，用于检测 JSON-RPC 响应（区分响应与通知）。 */
    private val json = Json { ignoreUnknownKeys = true }

    /** 子进程实例；connect() 后非 null。 */
    private var process: Process? = null

    /** 子进程标准输入写入器；connect() 后非 null。 */
    private var stdin: BufferedWriter? = null

    /** 子进程标准输出读取器；connect() 后非 null。 */
    private var stdout: BufferedReader? = null

    /** 消息通道，后台读线程写入，request() 读取。容量无限制以缓冲通知。 */
    private val messageChannel = Channel<String>(Channel.UNLIMITED)

    /** stdin 写入互斥锁，防止 request() 与 notify() 并发写入交错。 */
    private val writeMutex = Mutex()

    /** 后台读线程实例。 */
    private var readerThread: Thread? = null

    /** 是否已关闭。 */
    @Volatile
    private var closed = false

    /**
     * 启动子进程并初始化 I/O 流与后台读线程。
     *
     * - 实现：ProcessBuilder 构造命令 + 参数，设置工作目录与环境变量，start() 启动；
     *   获取 stdin (outputStream) 与 stdout (inputStream)；
     *   启动后台线程持续读取 stdout 行并放入 messageChannel
     */
    override suspend fun connect() {
        val command = mutableListOf(transport.command)
        command.addAll(transport.args)
        val pb = ProcessBuilder(command)
        // 设置工作目录
        transport.workingDir?.let { pb.directory(File(it)) }
        // 覆盖/追加环境变量
        transport.env.forEach { (k, v) -> pb.environment()[k] = v }
        val proc = pb.start()
        process = proc
        stdin = proc.outputStream.bufferedWriter()
        stdout = proc.inputStream.bufferedReader()
        // 启动后台读线程：持续读取 stdout 行并放入 Channel
        readerThread = Thread({ readLoop() }, "mcp-stdio-reader").apply { isDaemon = true; start() }
    }

    /**
     * 后台读取循环：从 stdout 逐行读取，放入 messageChannel。
     * - 当 stdout 关闭（子进程退出）时关闭 Channel 并退出循环
     */
    private fun readLoop() {
        val reader = stdout ?: return
        try {
            while (!closed) {
                val line = reader.readLine() ?: break
                messageChannel.trySend(line)
            }
        } catch (_: Exception) {
            // 读取异常（子进程崩溃等），关闭 Channel
        } finally {
            messageChannel.close()
        }
    }

    /**
     * 发送 JSON-RPC 请求并等待响应。
     *
     * - 实现：writeMutex 保护下写入 stdin → 从 messageChannel 读取 → 过滤通知 → 返回响应
     * - 超时：withTimeout 控制最大等待时间；超时抛 TimeoutCancellationException
     * - 过滤：跳过非 JSON 行与通知（无 result/error 字段的消息）
     *
     * @param message JSON-RPC 请求消息字符串
     * @return JSON-RPC 响应消息字符串
     */
    override suspend fun request(message: String): String {
        val writer = stdin ?: throw RACException("Stdio connection not connected")
        // 写入请求到子进程 stdin
        writeMutex.withLock {
            writer.write(message)
            writer.write("\n")
            writer.flush()
        }
        // 从 Channel 读取响应，跳过通知与无效行
        return withTimeout(timeoutMillis) {
            while (true) {
                val line = messageChannel.receive()
                if (line.isBlank()) continue
                // 检查是否为 JSON-RPC 响应（含 result 或 error 字段）
                try {
                    val element = json.parseToJsonElement(line)
                    val obj = element.jsonObject
                    if (obj.containsKey("result") || obj.containsKey("error")) {
                        return@withTimeout line
                    }
                    // 是通知（无 result/error），跳过继续读取
                } catch (_: Exception) {
                    // 非 JSON 行，跳过
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw RACException("Unreachable: withTimeout loop exited without return")
        }
    }

    /**
     * 发送 JSON-RPC 通知（不等待响应）。
     *
     * @param message JSON-RPC 通知消息字符串
     */
    override suspend fun notify(message: String) {
        val writer = stdin ?: throw RACException("Stdio connection not connected")
        writeMutex.withLock {
            writer.write(message)
            writer.write("\n")
            writer.flush()
        }
    }

    /**
     * 关闭连接：关闭流、销毁子进程。
     * - 边缘：幂等，多次调用安全
     */
    override suspend fun close() {
        if (closed) return
        closed = true
        try { stdin?.close() } catch (_: Exception) {}
        try { stdout?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        messageChannel.close()
    }
}
