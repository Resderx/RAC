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

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.BufferedWriter

/**
 * ACP 服务端 stdio 传输连接的 JVM 实现（actual）。
 *
 * - 作用：读取自身 stdin、写入自身 stdout，使 RAC 能作为 ACP Agent 子进程运行
 * - 必要性：ACP Agent 作为 Client 子进程运行时，通过自身 stdin/stdout 交换 JSON-RPC 消息；
 *   JVM 平台通过 System.in/System.out 访问标准流
 * - 设计思路：
 *   1. [connect]：获取 System.in 的 BufferedReader 与 System.out 的 BufferedWriter，
 *      启动后台读线程持续读取 stdin 行并推送到 _incoming
 *   2. [send]：writeMutex 保护下写入 stdout（换行分隔）
 *   3. [close]：关闭 reader/writer（不关闭 System.in/System.out 本身，避免影响其他代码）
 * - 与 [JvmAcpStdioConnection]（Client 端）的区别：
 *   - Client 端通过 ProcessBuilder 启动子进程，读取子进程 stdout、写入子进程 stdin
 *   - Server 端直接读取自身 stdin、写入自身 stdout
 * - 线程安全：writeMutex 保护 stdout 写入；MutableSharedFlow 线程安全
 * - 边缘：stdin 关闭（Client 断开）时 _incoming 推送空行作为终止信号
 * - 时间复杂度：单次 send O(n) 字符串写入 + I/O
 * - 空间复杂度：O(n) SharedFlow 缓冲
 */
@Throws(UnsupportedOperationException::class)
internal actual fun createAcpStdioServerConnection(): AcpConnection = JvmAcpStdioServerConnection()

/**
 * JVM 平台 ACP 服务端 stdio 连接实现。
 *
 * - 读取自身 stdin（Client→Agent 消息），写入自身 stdout（Agent→Client 消息）
 */
private class JvmAcpStdioServerConnection : AcpConnection {

    /** stdin 读取器；connect() 后非 null。 */
    private var stdin: BufferedReader? = null

    /** stdout 写入器；connect() 后非 null。 */
    private var stdout: BufferedWriter? = null

    /** incoming 消息的内部可变 SharedFlow，后台读线程向其推送消息。 */
    private val _incoming = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = Channel.UNLIMITED,
    )

    /** 暴露给消费者的只读 incoming 流。 */
    override val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    /** stdout 写入互斥锁。 */
    private val writeMutex = Mutex()

    /** 后台读线程。 */
    private var readerThread: Thread? = null

    /** 是否已关闭。 */
    @Volatile
    private var closed = false

    /**
     * 初始化 I/O 流并启动后台读线程。
     *
     * - 实现：获取 System.in 的 BufferedReader 与 System.out 的 BufferedWriter，
     *   启动守护线程持续读取 stdin
     */
    override suspend fun connect() {
        stdin = System.`in`.bufferedReader()
        stdout = System.out.bufferedWriter()
        readerThread = Thread({ readLoop() }, "acp-server-stdio-reader").apply { isDaemon = true; start() }
    }

    /**
     * 后台读取循环：从 stdin 逐行读取，推送到 _incoming。
     * - stdin 关闭时推送空行作为终止信号
     */
    private fun readLoop() {
        val reader = stdin ?: return
        try {
            while (!closed) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) {
                    _incoming.tryEmit(line)
                }
            }
        } catch (_: Exception) {
            // 读取异常，忽略
        } finally {
            _incoming.tryEmit("") // 终止信号
        }
    }

    /**
     * 发送一条 JSON-RPC 消息到 stdout。
     *
     * @param message JSON-RPC 消息字符串（MUST NOT 包含嵌入换行）
     */
    override suspend fun send(message: String) {
        val writer = stdout
            ?: throw top.resderx.rac.exceptions.RACException("ACP server stdio connection not connected")
        writeMutex.withLock {
            writer.write(message)
            writer.write("\n")
            writer.flush()
        }
    }

    /**
     * 关闭连接。
     * - 边缘：幂等；不关闭 System.in/System.out 本身，仅关闭包装的 reader/writer
     */
    override suspend fun close() {
        if (closed) return
        closed = true
        try { stdin?.close() } catch (_: Exception) {}
        try { stdout?.close() } catch (_: Exception) {}
    }
}
