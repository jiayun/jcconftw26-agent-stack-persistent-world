package tw.springfestival.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.yaml.snakeyaml.Yaml
import tw.springfestival.ai.*
import tw.springfestival.application.*
import tw.springfestival.domain.*

@SpringBootApplication
class WorldApplication {
    @Bean fun persistenceJson(): ObjectMapper = jacksonObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
    @Bean fun rules(json: ObjectMapper): WorldRules {
        val raw: List<Map<String, Any?>> = javaClass.getResourceAsStream("/content/events.yaml").use { Yaml().load(it) }
        return WorldRules(raw.map { json.convertValue(it, StoryEvent::class.java) })
    }
    @Bean fun worldStore(jdbc: JdbcTemplate, manager: PlatformTransactionManager, json: ObjectMapper): WorldStore = JdbcWorldStore(jdbc, TransactionTemplate(manager), json)
    @Bean fun storyModel(
        @Value("\${world.mode}") mode: String,
        @Value("\${world.model}") model: String,
        @Value("\${world.api-key}") key: String,
        @Value("\${world.base-url}") baseUrl: String,
    ): StoryModel {
        require(mode in setOf("offline", "live")) { "WORLD_MODE 必須為 offline 或 live。" }
        val characters: Map<String, Map<String, String>> = javaClass.getResourceAsStream("/content/characters.yaml").use { Yaml().load(it) }
        require(characters.keys == setOf("Elia", "Miro") && characters.values.all { it.keys.containsAll(setOf("voice", "goal", "private")) }) { "角色聖經格式不正確。" }
        val voices = characters.mapValues { it.value.getValue("voice") }
        if (mode == "offline") return SpringStoryModel(null, mode, voices)
        require(model.isNotBlank() && key.isNotBlank()) { "live 模式需要設定 WORLD_MODEL 與 OPENAI_API_KEY。" }
        require(baseUrl.isNotBlank()) { "live 模式需要設定 OPENAI_BASE_URL。" }
        val chat = OpenAiChatModel.builder().options(OpenAiChatOptions.builder().baseUrl(baseUrl).apiKey(key).model(model).timeout(java.time.Duration.ofSeconds(40)).maxRetries(0).parallelToolCalls(false).build()).build()
        return SpringStoryModel(ChatClient.create(chat), mode, voices)
    }
    @Bean fun npcPlanner(): NpcPlanner = EmbabelNpcPlanner()
    @Bean fun workflow(rules: WorldRules, model: StoryModel, planner: NpcPlanner): TurnWorkflow = KoogTurnWorkflow(rules, model, planner)
    @Bean(destroyMethod = "close") fun game(store: WorldStore, rules: WorldRules, model: StoryModel, workflow: TurnWorkflow) = GameService(store, rules, model, workflow)
    @Bean fun recover(game: GameService) = ApplicationRunner { game.recover() }
}
fun main(args: Array<String>) { runApplication<WorldApplication>(*args) }
