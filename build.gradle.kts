// 根项目 build.gradle.kts
//
// - 作用：根项目构建配置；KMP/Android/Serialization/Maven Publish 插件由 buildSrc 中的
//   rac-kmp 约定插件统一应用，根项目无需显式声明 apply: false
// - 设计：与 JetBrains kotlinx 系列库一致——约定插件位于 buildSrc，子模块仅需 id("rac-kmp")
// - 注意：不在根 build.gradle.kts 的 plugins {} 块声明插件，避免与 buildSrc classpath 冲突

plugins {
    // 空实现——所有插件由 rac-kmp 约定插件管理
    // 如需添加根项目级插件（如 versions 插件），在此声明
}
