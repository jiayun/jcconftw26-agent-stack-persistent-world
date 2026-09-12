plugins { alias(libs.plugins.kotlin.jvm) }
dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation(platform(libs.spring.ai.bom))
    implementation("org.springframework.ai:spring-ai-client-chat")
    implementation("org.springframework.ai:spring-ai-openai")
    implementation(libs.embabel)
    implementation(libs.koog)
}
