plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.boot) apply false
}
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    group = "tw.springfestival"
    version = "0.1.0"
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21); compilerOptions {
        javaParameters.set(
            true
        )
    }
    }
    dependencyLocking { lockAllConfigurations() }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.14.0")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.14.0")
    }
}
