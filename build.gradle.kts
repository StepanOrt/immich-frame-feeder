plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

repositories {
    mavenCentral()
}

kotlin {
    linuxX64("native") {
        binaries {
            executable("KotlinNativeTemplate") {
                entryPoint = "main"
            }
        }
    }

    sourceSets {
        val nativeMain by getting {
            dependencies {
                implementation("io.arrow-kt:suspendapp:2.2.3")
                implementation("com.kgit2:kommand:2.3.0")
                implementation("io.ktor:ktor-client-core:3.6.0")
                implementation("io.ktor:ktor-client-curl:3.6.0")
                implementation("com.squareup.okio:okio:3.18.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
        val nativeTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("io.ktor:ktor-client-mock:3.6.0")
            }
        }
    }
}
