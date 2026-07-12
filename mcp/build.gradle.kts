// mcp/build.gradle.kts — rac-mcp 模块构建配置
//
// - 作用：MCP（Model Context Protocol）客户端支持，含 JsonRpc 协议类型、McpClient、
//   HTTP/Stdio 传输、以及 RAC.chatWithMcp 扩展函数
// - 依赖：rac-core（消息模型、网络层、异常类型）
// - 约定：KMP 目标平台、平台引擎依赖、Maven 发布配置由 rac-kmp 约定插件统一管理

plugins {
    id("rac-kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
        }
    }
}
