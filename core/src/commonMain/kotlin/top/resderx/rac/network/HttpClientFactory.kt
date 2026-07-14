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

package top.resderx.rac.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE

/**
 * HttpClient 工厂。
 *
 * 作用：创建预配置的 HttpClient 实例，统一封装平台引擎选择、超时与 SSE 插件安装。
 * 必要性：库的所有网络调用都需通过 HttpClient，若每个调用点自行创建会导致配置不一致与资源泄漏；
 *       集中工厂保证全平台行为统一，并便于在测试中替换为 MockEngine。
 * 设计思路：以 object 单例暴露 create 方法，内部调用 expect/actual 的 getEngine() 获取平台引擎，
 *          通过 Ktor 插件机制安装 SSE 与 HttpTimeout；timeoutMs 参数允许调用方按需覆盖默认超时。
 *          另提供 createWithEngine 方法供测试注入自定义引擎（如 MockEngine）。
 * 实现方式：HttpClient(getEngine()) { install(SSE); install(HttpTimeout) { ... } }。
 * 可能的问题：不同平台引擎对 SSE 长连接与并发连接数支持存在差异（如 WinHttp 在高并发下可能受限）；
 *            工厂不自动重连，重连由 RetryExecutor 处理。
 * 边缘情况：timeoutMs=0 表示完全无超时（适合流式长连接场景），此时跳过 HttpTimeout 配置；
 *          timeoutMs 为负数按 0 处理。
 * 优点：单一入口、配置一致、便于测试注入、零额外依赖。
 * 算法/数据结构：HttpClient 内部为基于引擎的协程管道，本工厂仅做装配，无额外数据结构。
 * 时间复杂度：create() 为 O(1)，仅做对象装配。
 * 空间复杂度：O(1)，工厂自身无状态；返回的 HttpClient 内存占用由引擎实现决定。
 */
public object HttpClientFactory {

    /**
     * 创建预配置的 HttpClient。
     *
     * 作用：装配平台引擎、SSE 插件、超时插件并返回 HttpClient。
     * 必要性：统一所有调用点的客户端创建逻辑。
     * 设计思路：超时仅在 timeoutMs>0 时配置，避免对流式长连接误超时。
     * 实现方式：Ktor 插件 DSL。
     * 边缘情况：timeoutMs<=0 时跳过 HttpTimeout 安装，使用引擎默认行为。
     *
     * @param timeoutMs 请求/连接/Socket 超时毫秒数，0 或负数表示无超时，默认 60000ms。
     * @return 预配置的 HttpClient，调用方负责 close()。
     */
    public fun create(timeoutMs: Long = 60_000L): HttpClient {
        return HttpClient(getEngine()) {
            configureSseAndTimeout(timeoutMs)
        }
    }

    /**
     * 使用指定引擎创建预配置的 HttpClient（主要供测试注入 MockEngine）。
     *
     * - 作用：允许调用方提供自定义 HttpClientEngine（如 MockEngine 实例），复用 SSE 与超时配置逻辑
     * - 必要性：测试需注入 MockEngine 模拟网络响应，但不能绕过工厂的插件配置
     * - 设计思路：与 [create] 共享插件安装逻辑，仅引擎来源不同
     * - 边缘情况：timeoutMs<=0 时跳过 HttpTimeout 安装
     *
     * @param engine 自定义 HttpClient 引擎（如 MockEngine 实例）
     * @param timeoutMs 请求/连接/Socket 超时毫秒数，0 或负数表示无超时，默认 60000ms。
     * @return 预配置的 HttpClient，调用方负责 close()。
     */
    public fun createWithEngine(engine: HttpClientEngine, timeoutMs: Long = 60_000L): HttpClient {
        return HttpClient(engine) {
            configureSseAndTimeout(timeoutMs)
        }
    }

    /**
     * 在 HttpClientConfig 中安装 SSE 与 HttpTimeout 插件（内部复用）。
     *
     * - 作用：统一 create 与 createWithEngine 的插件配置，避免重复代码
     * - 实现：安装 SSE 插件；timeoutMs>0 时安装 HttpTimeout 并设置三个超时字段
     *
     * @param timeoutMs 超时毫秒数，<=0 时跳过 HttpTimeout
     */
    private fun HttpClientConfig<*>.configureSseAndTimeout(timeoutMs: Long) {
        install(SSE)
        if (timeoutMs > 0L) {
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
                socketTimeoutMillis = timeoutMs
            }
        }
    }
}

