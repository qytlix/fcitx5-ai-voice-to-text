plugins {
    kotlin("jvm") version "1.9.22"
    id("org.jetbrains.kotlinx.benchmark") version "0.4.10"
}

group = "org.fcitx.fcitx5.android.voice"
version = "0.1.0"

// 仓库配置继承自 settings.gradle.kts

dependencies {
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // Retrofit + OkHttp (仅接口定义，运行时依赖由 Android 模块提供)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON 序列化
    implementation("com.google.code.gson:gson:2.10.1")

    // 测试
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.1")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

kotlin {
    jvmToolchain(17)
}
