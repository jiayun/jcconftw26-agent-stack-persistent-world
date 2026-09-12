package tw.springfestival.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.nio.file.Path
import kotlin.io.path.writeText

class EnvironmentConfigTest {
    @TempDir lateinit var directory: Path

    @Configuration(proxyBeanMethods = false)
    class Config

    private fun open(environmentValues: Map<String, Any> = emptyMap(), vararg args: String) =
        SpringApplicationBuilder(Config::class.java)
            .web(WebApplicationType.NONE)
            .environment(StandardEnvironment().apply {
                propertySources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environmentValues))
            })
            .run("--spring.config.import=optional:file:${directory.resolve(".env")}[.properties]",
                "--spring.main.banner-mode=off", *args)

    @Test fun `missing env keeps offline defaults`() {
        open().use {
            assertEquals("offline", it.environment.getProperty("world.mode"))
            assertEquals("", it.environment.getProperty("world.api-key"))
            assertEquals("https://api.openai.com/v1", it.environment.getProperty("world.base-url"))
        }
    }

    @Test fun `env loads connection settings and preserves JDBC semicolon`() {
        directory.resolve(".env").writeText("""
            # Sample values only
            WORLD_MODE=live
            WORLD_MODEL=sample-model
            OPENAI_API_KEY=sample-key
            OPENAI_BASE_URL=http://127.0.0.1:1234/v1
            PORT=8099
            WORLD_DATABASE_URL=jdbc:h2:mem:dotenv;DB_CLOSE_DELAY=-1
        """.trimIndent())
        open().use {
            val env = it.environment
            assertEquals("live", env.getProperty("world.mode"))
            assertEquals("sample-model", env.getProperty("world.model"))
            assertEquals("sample-key", env.getProperty("world.api-key"))
            assertEquals("http://127.0.0.1:1234/v1", env.getProperty("world.base-url"))
            assertEquals("8099", env.getProperty("server.port"))
            assertEquals("jdbc:h2:mem:dotenv;DB_CLOSE_DELAY=-1", env.getProperty("spring.datasource.url"))
        }
    }

    @Test fun `environment overrides env file and command line overrides environment`() {
        directory.resolve(".env").writeText("WORLD_MODEL=file-model\nOPENAI_API_KEY=file-key\n")
        open(mapOf("WORLD_MODEL" to "environment-model")).use {
            assertEquals("environment-model", it.environment.getProperty("world.model"))
        }
        open(mapOf("WORLD_MODEL" to "environment-model", "OPENAI_API_KEY" to "environment-key"),
            "--world.model=argument-model").use {
            assertEquals("argument-model", it.environment.getProperty("world.model"))
            assertEquals("environment-key", it.environment.getProperty("world.api-key"))
        }
    }
}
