// buildSrc/build.gradle.kts — buildSrc 构建配置，为约定插件提供 Gradle 插件依赖
//
// - 作用：将 Kotlin Multiplatform、Android KMP、Maven Publish、Serialization 等插件
//   作为依赖提供给 buildSrc 中的约定插件（rac-kmp.gradle.kts），使各子模块只需
//   `id("rac-kmp")` 即可统一应用 KMP 配置
// - 设计：版本与 gradle/libs.versions.toml 保持一致（硬编码，因 buildSrc 无法直接访问 version catalog）

plugins {
    `kotlin-dsl`
}

// buildSrc 需显式声明仓库，否则无法解析插件依赖
// 与 settings.gradle.kts 的 dependencyResolutionManagement 仓库保持一致
repositories {
    google {
        mavenContent {
            includeGroupAndSubgroups("androidx")
            includeGroupAndSubgroups("com.android")
            includeGroupAndSubgroups("com.google")
        }
    }
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Kotlin Gradle 插件——提供 kotlin("multiplatform") 与 kotlin("plugin.serialization")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    // Kotlin Serialization 插件——预编译脚本插件需显式添加序列化插件到 classpath
    implementation("org.jetbrains.kotlin:kotlin-serialization:2.4.0")
    // Android KMP 库插件——使用 plugin marker artifact 避免将全部 AGP 插件注入 classpath
    // （直接依赖 com.android.tools.build:gradle 会把 com.android.application 也放上 classpath，
    // 导致根 build.gradle.kts 的 alias(androidApplication) apply false 报版本冲突）
    implementation("com.android.kotlin.multiplatform.library:com.android.kotlin.multiplatform.library.gradle.plugin:9.0.1")
    // Vanniktech Maven Publish 插件——提供 Maven Central 发布与 GPG 签名
    implementation("com.vanniktech.maven.publish:com.vanniktech.maven.publish.gradle.plugin:0.37.0")
}
