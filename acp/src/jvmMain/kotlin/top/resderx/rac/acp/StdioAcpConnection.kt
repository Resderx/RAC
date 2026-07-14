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

package top.resderx.rac.acp

import top.resderx.rac.exceptions.RACException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

/**
 * ACP stdio 传输连接的 JVM 实现（actual）。
 *
 * - 作用：通过 [ProcessBuilder] 启动 Agent 子进程，经 stdin/stdout 交换换行分隔的 JSON-RPC 消息
 * - 必要性：ACP v1 基线传输为 stdio，JVM 平台需完整支持以连接本地 Agent（如 Claude Code、Codex CLI 等）
 * - 设计思路：
 *   1. [connect]：ProcessBuilder 启动子进程，获取 stdin/stdout 流
 *   2. 后台读线程：持续从 stdout 读取行，通过 [MutableSharedFlow] 推送给消费者
 *   3. [send]：writeMutex 保护下写入 stdin
 *   4. [close]：关闭流并销毁子进程
 * - 与 MCP StdioConnection 的差异：ACP 使用 SharedFlow 广播 incoming 消息（支持多消费者与双向请求），
 *   MCP 用 Channel 单消费者模式（仅 Client 发请求等响应）
 * - 线程安全：writeMutex 保护 stdin 写入；MutableSharedFlow 线程安全
 * - 边缘：子进程意外退出时 incoming 完成；close() 幂等
 * - 时间复杂度：单次 send O(n) 字符串写入 + I/O
 * - 空间复杂度：O(n) SharedFlow 重放缓冲（n 为未消费消息数）
 */
@Throws(UnsupportedOperationException::class)
internal actual fun createAcpStdioConnection(
    transport: AcpStdioTransport,
): AcpConnection = JvmAcpStdioConnection(transport)

/**
 * JVM 平台 ACP stdio 连接实现。
 *
 * @property transport stdio 传输配置
 */
private class JvmAcpStdioConnection(
    private val transport: AcpStdioTransport,
) : AcpConnection {

    /** 子进程实例；connect() 后非 null。 */
    private var process: Process? = null

    /** 子进程标准输入写入器。 */
    private var stdin: BufferedWriter? = null

    /** 子进程标准输出读取器。 */
    private var stdout: BufferedReader? = null

    /** incoming 消息的内部可变 SharedFlow，后台读线程向其推送消息。 */
    private val _incoming = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = Channel.UNLIMITED,
    )

    /** 暴露给消费者的只读 incoming 流。 */
    override val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    /** stdin 写入互斥锁，防止并发写入交错。 */
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
     *   启动后台线程持续读取 stdout 行并 tryEmit 到 _incoming
     */
    override suspend fun connect() {
        val command = mutableListOf(transport.command)
        command.addAll(transport.args)
        val pb = ProcessBuilder(command)
        transport.workingDir?.let { pb.directory(File(it)) }
        transport.env.forEach { (k, v) -> pb.environment()[k] = v }
        val proc = pb.start()
        process = proc
        stdin = proc.outputStream.bufferedWriter()
        stdout = proc.inputStream.bufferedReader()
        // 启动后台读线程：持续读取 stdout 行并推送到 _incoming
        readerThread = Thread({ readLoop() }, "acp-stdio-reader").apply { isDaemon = true; start() }
    }

    /**
     * 后台读取循环：从 stdout 逐行读取，推送到 _incoming。
     * - 当 stdout 关闭（子进程退出）时完成 _incoming 并退出循环
     */
    private fun readLoop() {
        val reader = stdout ?: return
        try {
            while (!closed) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) {
                    _incoming.tryEmit(line)
                }
            }
        } catch (_: Exception) {
            // 读取异常（子进程崩溃等），忽略
        } finally {
            // 子进程退出后完成 incoming 流
            _incoming.tryEmit("") // 发送空行作为终止信号（上层过滤）
        }
    }

    /**
     * 发送一条 JSON-RPC 消息到子进程 stdin。
     *
     * @param message JSON-RPC 消息字符串（MUST NOT 包含嵌入换行）
     */
    override suspend fun send(message: String) {
        val writer = stdin ?: throw RACException("ACP stdio connection not connected")
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
    }
}
