plugins {
    alias(libs.plugins.android.application) apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 内置的 KGP 为 2.2.10，而 Compose BOM / kotlinx-serialization 已按 Kotlin 2.4
        // 的 metadata 版本编译。此处显式提升 KGP 与两个插件的版本，保证编译器与依赖一致。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        classpath(libs.compose.compiler.gradle.plugin)
        classpath(libs.kotlin.serialization.gradle.plugin)
    }
}
