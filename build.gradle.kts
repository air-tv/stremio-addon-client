import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = "com.getair"
version = providers.gradleProperty("VERSION_NAME").getOrElse("0.1.0-SNAPSHOT")

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    jvmToolchain(17)

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    linuxX64()
    mingwX64()
    macosX64()
    macosArm64()
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    js(IR) {
        browser()
        nodejs()
    }
    wasmJs {
        browser()
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.cio)
        }
    }
}

android {
    namespace = "com.getair.stremio"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Air Stremio Addon Client")
            description.set("A safe Kotlin Multiplatform client for the Stremio addon protocol.")
            url.set("https://github.com/get-air/stremio-addon-client")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                    distribution.set("repo")
                }
            }
            scm {
                url.set("https://github.com/get-air/stremio-addon-client")
                connection.set("scm:git:https://github.com/get-air/stremio-addon-client.git")
                developerConnection.set("scm:git:ssh://git@github.com/get-air/stremio-addon-client.git")
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/get-air/stremio-addon-client")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
        providers.gradleProperty("TEMP_MAVEN_REPOSITORY").orNull?.let { temporaryRepository ->
            maven {
                name = "HostTest"
                url = uri(temporaryRepository)
            }
        }
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
        rename { "LICENSE" }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    if (name.endsWith("ToGitHubPackagesRepository")) {
        val releaseVersionProvider = providers.gradleProperty("VERSION_NAME")
        val tagProvider = providers.environmentVariable("GITHUB_REF_NAME")
        val refTypeProvider = providers.environmentVariable("GITHUB_REF_TYPE")
        doFirst {
            val releaseVersion = releaseVersionProvider.orNull.orEmpty()
            val tag = tagProvider.orNull
            val refType = refTypeProvider.orNull
            val stableVersion = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
            require(stableVersion.matches(releaseVersion)) {
                "GitHub Packages requires a stable MAJOR.MINOR.PATCH VERSION_NAME"
            }
            require(refType == "tag" && tag == "v$releaseVersion") {
                "GitHub Packages VERSION_NAME must exactly match the vMAJOR.MINOR.PATCH tag"
            }
        }
    }
}
