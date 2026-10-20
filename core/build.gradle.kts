plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Java 17 bytecode, built with whatever JDK runs Gradle (Android Studio ships one)
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // Optional live test against a running lustr server - see LiveServerTest.kt
    listOf("LUSTR_TEST_URL", "LUSTR_TEST_USER", "LUSTR_TEST_PASSWORD").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}
