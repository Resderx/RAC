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
