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

// a2a/build.gradle.kts — rac-a2a 模块构建配置
//
// - 作用：A2A（Agent-to-Agent Protocol）双向支持，含 A2aClient、A2aAgentServer、
//   RacA2aAgent 适配器、以及 RAC.chatWithA2aAgent / RAC.serveAsA2aAgent 扩展函数
// - 依赖：rac-core（RAC 实例、消息模型）、rac-mcp（JsonRpc 协议类型）
// - 约定：KMP 目标平台、平台引擎依赖、Maven 发布配置由 rac-kmp 约定插件统一管理

plugins {
    id("rac-kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":rac-core"))
            implementation(project(":rac-mcp"))
        }
    }
}
