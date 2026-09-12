plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.spring); alias(libs.plugins.boot) }
dependencies {
    implementation(project(":domain")); implementation(project(":application")); implementation(project(":ai-adapters"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation(platform(libs.spring.ai.bom))
    implementation("org.springframework.ai:spring-ai-client-chat")
    implementation("org.springframework.ai:spring-ai-openai")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.2")
    implementation("org.yaml:snakeyaml:2.4")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
val frontendBuild by tasks.registering(Exec::class) {
    workingDir(rootProject.file("frontend"))
    commandLine("npm", "run", "build")
    inputs.dir(rootProject.file("frontend/src")); inputs.file(rootProject.file("frontend/package-lock.json"))
    outputs.dir(rootProject.file("frontend/dist"))
}
tasks.processResources { from(rootProject.file("content")) { into("content") } }
tasks.bootJar {
    dependsOn(frontendBuild)
    from(rootProject.file("frontend/dist")) { into("BOOT-INF/classes/static") }
}

tasks.bootRun { workingDir(rootProject.projectDir) }
