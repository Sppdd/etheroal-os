import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: the agent loop, tool policy and redaction have no Android or Firebase dependency,
// so every safety rule is unit-testable on the JVM with a fake model.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:ai"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
