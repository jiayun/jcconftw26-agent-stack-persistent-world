package tw.springfestival.server

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.net.URI

@Component
class ConnectionTokens(private val jdbc: JdbcTemplate) {
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun issue(saveId: String): Map<String, String> {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val id = UUID.randomUUID().toString()
        jdbc.update("INSERT INTO connection_tokens(id, save_id, token_hash) VALUES (?, ?, ?)", id, saveId, hash(token))
        return mapOf("id" to id, "token" to token)
    }
    fun authorize(header: String?): String? {
        if (header == null || !header.startsWith("Bearer ") || header.length > 150) return null
        return jdbc.query("SELECT save_id FROM connection_tokens WHERE token_hash = ? AND revoked = FALSE", { rs, _ -> rs.getString(1) }, hash(header.substring(7))).firstOrNull()
    }
    fun list(saveId: String) = jdbc.query("SELECT id, revoked FROM connection_tokens WHERE save_id = ?", { rs, _ -> mapOf("id" to rs.getString(1), "revoked" to rs.getBoolean(2)) }, saveId)
    fun revoke(saveId: String, tokenId: String): Map<String, Boolean> {
        jdbc.update("UPDATE connection_tokens SET revoked = TRUE WHERE id = ? AND save_id = ?", tokenId, saveId)
        return mapOf("revoked" to true)
    }
}
@Component
class LocalAccessFilter(private val tokens: ConnectionTokens) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val localHosts = setOf("localhost", "127.0.0.1", "[::1]", "::1")
        if (request.serverName !in localHosts) { response.sendError(403, "只接受本機主機名稱。"); return }
        val origin = request.getHeader("Origin")
        if (origin != null && runCatching { val uri = URI(origin); uri.host in localHosts && uri.port == request.serverPort && uri.scheme == "http" }.getOrDefault(false).not()) {
            response.sendError(403, "此來源未授權。"); return
        }
        if (request.contentLengthLong > 2_000_000) { response.sendError(413); return }
        if (request.requestURI.startsWith("/mcp")) {
            val saveId = tokens.authorize(request.getHeader("Authorization"))
            if (saveId == null) { response.sendError(401, "連線權杖無效或已撤銷。"); return }
            request.setAttribute("authorizedSave", saveId)
        } else if (request.requestURI.startsWith("/api") && request.method in setOf("POST", "PATCH") && request.contentType?.startsWith("application/json") != true) {
            response.sendError(415, "請使用 application/json。"); return
        }
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("Cache-Control", "no-store")
        chain.doFilter(request, response)
    }
}
