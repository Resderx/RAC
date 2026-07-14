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

// core/build.gradle.kts — rac-core 模块构建配置
//
// - 作用：RAC 核心模块，包含消息模型、网络层、API 协议客户端、供应商实现与基础 DSL
// - 依赖：无 RAC 内部模块依赖（此为根模块）
// - 约定：KMP 目标平台、平台引擎依赖、Maven 发布配置由 rac-kmp 约定插件统一管理

plugins {
    id("rac-kmp")
}
