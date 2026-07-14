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

// rac-kmp.gradle.kts — RAC 项目 KMP 约定插件
//
// - 作用：统一配置所有子模块的 Kotlin Multiplatform 目标平台、依赖版本、Maven 发布元数据
// - 必要性：4 个模块（core/mcp/acp/a2a）共享相同的 KMP 目标与平台引擎依赖，
//   约定插件避免重复 60+ 行配置，遵循 JetBrains kotlinx 系列库的最佳实践
// - 设计思路：
//   1. 应用 KMP + Android KMP + Serialization + Maven Publish 插件
//   2. 配置 9 个目标平台（JVM/Android/iOS/mingwX64/linuxX64/linuxArm64/macosArm64/JS/WasmJs）
//   3. 按平台配置 Ktor 引擎依赖（OkHttp/Darwin/Curl/WinHttp/JS）
//   4. 配置通用测试依赖（kotlin-test + ktor-mock）
//   5. 设置 Maven Central 发布元数据（POM、签名、仓库）
// - 各模块仅需在 build.gradle.kts 中 `id("rac-kmp")` 并追加模块专属依赖
// - 版本与 gradle/libs.versions.toml 保持一致

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.vanniktech.maven.publish")
    kotlin("plugin.serialization")
}

// ── 坐标 ──────────────────────────────────────────────────────────────────

group = "top.resderx.rac"
version = "0.1.0-alpha01"

// ── KMP 目标平台 ──────────────────────────────────────────────────────────

kotlin {
    // iOS（Arm64 + Simulator）
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    jvm()

    js {
        useEsModules()
        generateTypeScriptDefinitions()
        binaries.executable()

        browser()
        nodejs()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        useEsModules()
        generateTypeScriptDefinitions()
        binaries.executable()

        browser()
        nodejs()
    }

    mingwX64()

    linuxX64()
    linuxArm64()

    macosArm64()
    macosX64()

    tvosX64()
    tvosArm64()
    tvosSimulatorArm64()

    watchosArm32()
    watchosArm64()
    watchosSimulatorArm64()

    androidLibrary {
        namespace = "top.resderx.rac"
        compileSdk = 36
        minSdk = 24
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    // ── 平台依赖 ──────────────────────────────────────────────────────────

    sourceSets {
        appleMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.5.1")
        }

        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.5.1")
        }

        commonMain.dependencies {
            implementation("io.ktor:ktor-client-core:3.5.1")
            implementation("io.ktor:ktor-client-cio:3.5.1")
            implementation("io.ktor:ktor-client-websockets:3.5.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-schema-generator-json:0.4.4")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("io.ktor:ktor-client-mock:3.5.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }

        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.5.1")
        }

        linuxMain.dependencies {
            implementation("io.ktor:ktor-client-curl:3.5.1")
        }

        mingwMain.dependencies {
            implementation("io.ktor:ktor-client-winhttp:3.5.1")
            implementation("io.ktor:ktor-client-curl:3.5.1")
        }

        webMain.dependencies {
            implementation("io.ktor:ktor-client-js:3.5.1")
        }
    }
}

// ── Maven Central 发布配置 ────────────────────────────────────────────────
//
// vanniktech.maven.publish 插件通过 gradle.properties / 环境变量读取凭证：
//   mavenCentralUsername / mavenCentralPassword  — Central Portal 令牌
//   signingInMemoryKey / signingInMemoryKeyId / signingInMemoryKeyPassword — GPG 签名
// 在 CI 中通过 GitHub Secrets 注入。

mavenPublishing {
    coordinates(group as String, project.name, version as String)

    // POM 元数据
    pom {
        name.set("RAC — ${project.name}")
        description.set("Resderx AI Call — Kotlin Multiplatform AI 模型调用库")
        inceptionYear.set("2026")
        url.set("https://github.com/Resderx/RAC")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("Resderx")
                name.set("Resderx")
                url.set("https://github.com/Resderx")
            }
        }

        scm {
            url.set("https://github.com/Resderx/RAC")
            connection.set("scm:git:git://github.com/Resderx/RAC.git")
            developerConnection.set("scm:git:ssh://github.com/Resderx/RAC.git")
        }
    }
}
