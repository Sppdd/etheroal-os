import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: the phonemizer (text -> phoneme ids for tiny-voca) has no Android dependency,
// so it is unit-tested on the JVM against the reference Python output.
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
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
