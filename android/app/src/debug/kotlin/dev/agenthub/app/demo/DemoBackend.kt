package dev.agenthub.app.demo

import dev.agenthub.app.agents.catalog.PermissionRules
import dev.agenthub.app.agents.catalog.TemplateCatalog
import dev.agenthub.app.auth.data.GoogleSignInRequest
import dev.agenthub.app.auth.data.RefreshRequest
import dev.agenthub.app.auth.data.TokenResponse
import dev.agenthub.app.core.network.dto.AgentDto
import dev.agenthub.app.core.network.dto.ApprovalDto
import dev.agenthub.app.core.network.dto.ConnectionDto
import dev.agenthub.app.core.network.dto.ConversationDto
import dev.agenthub.app.core.network.dto.CreateAgentRequest
import dev.agenthub.app.core.network.dto.DecisionRequest
import dev.agenthub.app.core.network.dto.ExportDto
import dev.agenthub.app.core.network.dto.LlmProfileDto
import dev.agenthub.app.core.network.dto.LlmProfileRequest
import dev.agenthub.app.core.network.dto.LogEntryDto
import dev.agenthub.app.core.network.dto.MessageDto
import dev.agenthub.app.core.network.dto.OAuthStartDto
import dev.agenthub.app.core.network.dto.RunDto
import dev.agenthub.app.core.network.dto.TestResultDto
import dev.agenthub.app.core.network.dto.UpdateAgentRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.buffer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fake Agent Hub server for debug builds, implemented as an OkHttp interceptor so the whole app (Retrofit,
 * SSE, repositories, caches, ViewModels, screens) runs for real against it. It exists only in the debug source
 * set, so it cannot ship in a release build. Every name, email and number it returns is made up. It follows
 * docs/API_CONTRACT.md and applies the permission rules of architecture 2.1, but it is NOT the Tool Gateway
 * and proves nothing about the real backend.
 *
 * @param latencyMs pause before each plain response, so loading states are visible.
 * @param speed multiplies every streaming pause; 0 removes them (used by tests).
 */
class DemoBackend(
    private val latencyMs: Long = 250,
    private val speed: Double = 1.0,
    private val clock: () -> Instant = { Instant.now() },
) : Interceptor {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val lock = Any()
    private val ids = AtomicInteger(100)

    private val agents = LinkedHashMap<String, AgentDto>()
    private val conversations = LinkedHashMap<String, ConversationDto>()
    private val messages = LinkedHashMap<String, MutableList<MessageDto>>()
    private val runs = LinkedHashMap<String, Run>()
    private val approvals = LinkedHashMap<String, Pending>()
    private val logs = ArrayList<LogEntryDto>()
    private val profiles = LinkedHashMap<String, LlmProfileDto>()
    private val profileKeys = HashMap<String, String>()

    private class Run(val id: String, val agentId: String, val conversationId: String) {
        @Volatile var status = "running"
        @Volatile var errorCode: String? = null
        @Volatile var cancelled = false
    }

    private class Pending(var approval: ApprovalDto, val run: Run, val intent: Intent, val toolCallId: String)

    init { seed() }

    private fun seed() {
        val template = TemplateCatalog.byId("github-assistant")!!
        val defaults = PermissionRules.defaults(template)
        val toggles = defaults.toggles + ("edit_profile" to true)
        agents["agent-1"] = AgentDto(
            id = "agent-1", name = "Demo GitHub", templateId = template.id, connector = "github",
            instructions = template.defaultInstructions, status = "active", llmProfileId = "llm-1",
            timeZone = "Asia/Kolkata", toggles = toggles,
            approvalModes = defaults.approvalModes.mapValues { it.value.wire },
            connection = ConnectionDto("active", scopesFor("github", toggles)),
        )
        profiles["llm-1"] = LlmProfileDto("llm-1", "Demo Claude", "anthropic", null, "claude-demo", 0.2, 1000, "demo", true)
        profileKeys["llm-1"] = "sk-demo-key-demo"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (latencyMs > 0 && speed > 0) pause((latencyMs * speed).toLong())
        return try {
            synchronized(lock) { route(request) }
        } catch (e: BadRequest) {
            error(request, 400, "bad_request")
        } catch (e: kotlinx.serialization.SerializationException) {
            error(request, 400, "bad_request")
        }
    }

    private class BadRequest : Exception()

    // ---------------------------------------------------------------- routing

    private fun route(r: Request): Response {
        val seg = r.url.pathSegments.filter { it.isNotEmpty() }
        val m = r.method
        fun at(i: Int) = seg.getOrNull(i)
        return when (seg.firstOrNull()) {
            "auth" -> auth(r, m, at(1))
            "agents" -> when {
                at(1) == "kill-switch" && m == "POST" -> killSwitch(r)
                seg.size == 1 && m == "GET" -> ok(r, agents.values.toList().asReversed())
                seg.size == 1 && m == "POST" -> createAgent(r)
                seg.size == 2 && m == "PATCH" -> updateAgent(r, at(1)!!)
                seg.size == 2 && m == "DELETE" -> deleteAgent(r, at(1)!!)
                at(2) == "connection" && m == "DELETE" -> disconnect(r, at(1)!!)
                at(2) == "conversations" -> agentConversations(r, m, at(1)!!)
                at(2) == "chat" && m == "POST" -> chat(r, at(1)!!)
                at(2) == "logs" && m == "GET" -> listLogs(r, at(1)!!)
                else -> notFound(r)
            }
            "oauth" -> if (at(2) == "start") oauthStart(r, at(1)!!) else notFound(r)
            "conversations" -> when {
                at(2) == "messages" && m == "GET" -> listMessages(r, at(1)!!)
                seg.size == 2 && m == "DELETE" -> deleteConversation(r, at(1)!!)
                else -> notFound(r)
            }
            "runs" -> runRoute(r, m, seg)
            "approvals" -> when {
                seg.size == 1 && m == "GET" -> ok(r, approvals.values.map { it.approval }.filter { it.status == "pending" })
                at(2) == "decision" && m == "POST" -> decide(r, at(1)!!)
                else -> notFound(r)
            }
            "llm-profiles" -> llm(r, m, seg)
            "devices" -> noContent(r)
            "account" -> account(r, m, at(1))
            else -> notFound(r)
        }
    }

    // ------------------------------------------------------------------- auth

    private fun auth(r: Request, m: String, action: String?): Response {
        if (m != "POST") return notFound(r)
        return when (action) {
            "google" -> { body<GoogleSignInRequest>(r); ok(r, TokenResponse("demo-access-${ids.incrementAndGet()}", "demo-refresh-${ids.incrementAndGet()}", 900)) }
            "refresh" -> { body<RefreshRequest>(r); ok(r, TokenResponse("demo-access-${ids.incrementAndGet()}", "demo-refresh-${ids.incrementAndGet()}", 900)) }
            "logout" -> noContent(r)
            else -> notFound(r)
        }
    }

    // ----------------------------------------------------------------- agents

    private fun createAgent(r: Request): Response {
        val req = body<CreateAgentRequest>(r)
        val template = TemplateCatalog.byId(req.templateId) ?: return error(r, 400, "unknown_template")
        val clean = PermissionRules.sanitize(
            template,
            dev.agenthub.app.core.model.AgentPermissions(req.toggles, req.approvalModes.mapValues { dev.agenthub.app.core.model.ApprovalMode.fromWire(it.value) }),
        )
        val id = "agent-${ids.incrementAndGet()}"
        val dto = AgentDto(
            id = id, name = req.name, templateId = template.id, connector = template.connector.wire, instructions = req.instructions,
            status = "draft", llmProfileId = req.llmProfileId ?: profiles.values.firstOrNull { it.isDefault }?.id, timeZone = req.timeZone,
            toggles = clean.toggles, approvalModes = clean.approvalModes.mapValues { it.value.wire },
        )
        agents[id] = dto
        return ok(r, dto, 201)
    }

    private fun updateAgent(r: Request, id: String): Response {
        val current = agents[id] ?: return notFound(r)
        val req = body<UpdateAgentRequest>(r)
        val template = TemplateCatalog.byId(current.templateId)
        var next = current.copy(
            name = req.name ?: current.name,
            instructions = req.instructions ?: current.instructions,
            llmProfileId = req.llmProfileId ?: current.llmProfileId,
            timeZone = req.timeZone ?: current.timeZone,
            status = req.status ?: current.status,
        )
        if (req.toggles != null || req.approvalModes != null) {
            val wanted = dev.agenthub.app.core.model.AgentPermissions(
                req.toggles ?: current.toggles,
                (req.approvalModes ?: current.approvalModes).mapValues { dev.agenthub.app.core.model.ApprovalMode.fromWire(it.value) },
            )
            val clean = if (template != null) PermissionRules.sanitize(template, wanted) else wanted
            next = next.copy(toggles = clean.toggles, approvalModes = clean.approvalModes.mapValues { it.value.wire })
        }
        agents[id] = next
        return ok(r, next)
    }

    private fun deleteAgent(r: Request, id: String): Response {
        if (agents.remove(id) == null) return notFound(r)
        conversations.values.filter { it.agentId == id }.map { it.id }.forEach { conversations.remove(it); messages.remove(it) }
        approvals.values.removeAll { it.approval.agentId == id }
        return noContent(r)
    }

    private fun disconnect(r: Request, id: String): Response {
        val a = agents[id] ?: return notFound(r)
        agents[id] = a.copy(connection = null, status = "draft")
        return noContent(r)
    }

    private fun killSwitch(r: Request): Response {
        agents.keys.toList().forEach { k -> agents[k] = agents[k]!!.copy(status = "paused") }
        return noContent(r)
    }

    private fun oauthStart(r: Request, connector: String): Response {
        val agent = agents[r.url.queryParameter("agent_id")] ?: return notFound(r)
        if (agent.connector != connector) return error(r, 400, "bad_request")
        // The real flow ends at the provider; here consent is granted instantly so the demo can continue.
        agents[agent.id] = agent.copy(
            connection = ConnectionDto("active", scopesFor(connector, agent.toggles)),
            status = if (agent.status == "paused") "paused" else "active",
        )
        return ok(r, OAuthStartDto("https://example.com/demo-consent?agent=${agent.id}"))
    }

    private fun scopesFor(connector: String, toggles: Map<String, Boolean>): List<String> {
        fun on(id: String) = toggles[id] == true
        return when (connector) {
            "github" -> buildList {
                if (on("read_prs")) add("pull_requests:read")
                if (on("read_followers")) add("followers:read")
                if (on("read_profile")) add("profile:read")
                if (on("edit_profile") || on("follow_unfollow")) add("user:write")
            }
            "gmail" -> buildList {
                if (on("read_email")) add("gmail.readonly")
                if (on("organize_email")) add("gmail.modify")
                if (on("draft_replies")) add("gmail.compose")
                if (on("send_email")) add("gmail.send")
            }
            else -> buildList {
                if (on("read_calendar")) add("calendar.readonly")
                if (on("edit_events") || on("invite_people") || on("delete_events")) add("calendar.events")
            }
        }
    }

    // ---------------------------------------------------- conversations & chat

    private fun agentConversations(r: Request, m: String, agentId: String): Response {
        if (agents[agentId] == null) return notFound(r)
        return when (m) {
            "GET" -> ok(r, conversations.values.filter { it.agentId == agentId }.sortedByDescending { it.updatedAt })
            "POST" -> ok(r, newConversation(agentId), 201)
            "DELETE" -> {
                conversations.values.filter { it.agentId == agentId }.map { it.id }.forEach { conversations.remove(it); messages.remove(it) }
                noContent(r)
            }
            else -> notFound(r)
        }
    }

    private fun newConversation(agentId: String): ConversationDto {
        val c = ConversationDto("conv-${ids.incrementAndGet()}", agentId, null, now().toString())
        conversations[c.id] = c
        messages[c.id] = mutableListOf()
        return c
    }

    private fun deleteConversation(r: Request, id: String): Response {
        if (conversations.remove(id) == null) return notFound(r)
        messages.remove(id)
        return noContent(r)
    }

    private fun listMessages(r: Request, conversationId: String): Response {
        val list = messages[conversationId] ?: return notFound(r)
        val after = r.url.queryParameter("after_seq")?.toLongOrNull() ?: 0L
        return ok(r, list.filter { it.seq > after })
    }

    private fun chat(r: Request, agentId: String): Response {
        val agent = agents[agentId] ?: return notFound(r)
        val req = json.parseToJsonElement(bodyText(r)) as? JsonObject ?: throw BadRequest()
        val conversationId = (req["conversation_id"] as? JsonPrimitive)?.content ?: throw BadRequest()
        val text = (req["message"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: throw BadRequest()
        val conversation = conversations[conversationId]?.takeIf { it.agentId == agentId } ?: return notFound(r)

        // One active run per conversation (architecture 3.4).
        if (runs.values.any { it.conversationId == conversationId && it.status == "running" }) return error(r, 409, "agent_busy")

        val run = Run("run-${ids.incrementAndGet()}", agentId, conversationId).also { runs[it.id] = it }
        addMessage(conversationId, "user", text(text))
        conversations[conversationId] = conversation.copy(title = conversation.title ?: text.take(40), updatedAt = now().toString())

        val events = Events(run)
        val chunks = mutableListOf(events.chunk(0, "run.started", buildJsonObject { put("run_id", run.id); put("conversation_id", conversationId) }))

        when {
            agent.connection == null || agent.connection!!.status != "active" -> {
                run.status = "failed"; run.errorCode = "connection_needs_reauth"
                chunks += events.chunk(250, "run.error", buildJsonObject { put("code", "connection_needs_reauth") })
            }
            agent.status != "active" ->
                chunks += answer(events, run, "This agent is paused. Resume it from its screen and ask again.")
            else -> when (val plan = DemoScripts.plan(agent, text)) {
                is Plan.Answer -> chunks += answer(events, run, plan.text)
                is Plan.Denied -> chunks += answer(events, run, DemoScripts.deniedText(plan.toggleLabel))
                is Plan.Run -> chunks += runTool(events, run, agent, plan.intent)
                is Plan.NeedsApproval -> chunks += needApproval(events, run, agent, plan)
            }
        }
        return Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Type", "text/event-stream")
            .body(ScriptedSource(chunks) { run.cancelled }.buffer().asResponseBody("text/event-stream".toMediaType(), -1L))
            .build()
    }

    private inner class Events(val run: Run) {
        private var next = 1
        fun chunk(delayMs: Long, event: String, data: JsonObject, onStart: (() -> Unit)? = null): Chunk {
            val id = next++
            return Chunk(scale(delayMs), "id: $id\nevent: $event\ndata: $data\n\n".encodeUtf8(), onStart)
        }
    }

    private fun deltas(events: Events, text: String, firstDelay: Long): List<Chunk> =
        Regex("""\S+\s*""").findAll(text).mapIndexed { i, piece ->
            events.chunk(if (i == 0) firstDelay else 40, "message.delta", buildJsonObject { put("text", piece.value) })
        }.toList()

    private fun answer(events: Events, run: Run, text: String): List<Chunk> =
        deltas(events, text, 300) + events.chunk(100, "run.completed", buildJsonObject { put("message_id", "msg-${run.id}") }) {
            synchronized(lock) {
                addMessage(run.conversationId, "assistant", text(text))
                finish(run)
            }
        }

    private fun runTool(events: Events, run: Run, agent: AgentDto, intent: Intent): List<Chunk> {
        val callId = "call-${ids.incrementAndGet()}"
        return buildList {
            add(events.chunk(450, "tool.started", buildJsonObject { put("tool_call_id", callId); put("tool", intent.tool) }))
            add(events.chunk(900, "tool.finished", buildJsonObject {
                put("tool_call_id", callId); put("tool", intent.tool); put("summary", intent.summary); put("ok", true)
            }))
            addAll(deltas(events, intent.answer, 250))
            add(events.chunk(100, "run.completed", buildJsonObject { put("message_id", "msg-${run.id}") }) {
                synchronized(lock) {
                    addMessage(run.conversationId, "assistant", toolCall(callId, intent))
                    addMessage(run.conversationId, "tool", toolResult(callId, intent.summary))
                    addMessage(run.conversationId, "assistant", text(intent.answer))
                    addLog(agent.id, intent, intent.summary)
                    finish(run)
                }
            })
        }
    }

    private fun needApproval(events: Events, run: Run, agent: AgentDto, plan: Plan.NeedsApproval): List<Chunk> {
        val callId = "call-${ids.incrementAndGet()}"
        val approvalId = "approval-${ids.incrementAndGet()}"
        return deltas(events, plan.preface, 300) + events.chunk(300, "approval.required", buildJsonObject {
            put("approval_id", approvalId); put("tool", plan.intent.tool)
        }) {
            synchronized(lock) {
                addMessage(run.conversationId, "assistant", text(plan.preface), toolCall(callId, plan.intent))
                approvals[approvalId] = Pending(
                    ApprovalDto(
                        id = approvalId, runId = run.id, agentId = agent.id, agentName = agent.name, tool = plan.intent.tool,
                        args = plan.intent.args, status = "pending", createdAt = now().toString(),
                        expiresAt = now().plus(Duration.ofHours(24)).toString(),
                    ),
                    run, plan.intent, callId,
                )
                run.status = "awaiting_approval"
            }
        }
    }

    private fun finish(run: Run) {
        if (run.status == "running") run.status = "completed"
        agents[run.agentId]?.let { agents[run.agentId] = it.copy(lastActivityAt = now().toString()) }
    }

    // --------------------------------------------------------------- approvals

    private fun decide(r: Request, id: String): Response {
        val pending = approvals[id] ?: return notFound(r)
        val decision = body<DecisionRequest>(r).decision
        // Same rule as the real server: one atomic change from pending, and nothing else ever runs.
        if (pending.approval.status != "pending") return error(r, 409, "approval_expired")
        if (Instant.parse(pending.approval.expiresAt).isBefore(now())) {
            pending.approval = pending.approval.copy(status = "expired")
            return error(r, 409, "approval_expired")
        }
        val run = pending.run
        val agent = agents[pending.approval.agentId]
        return when (decision) {
            "approve" -> {
                pending.approval = pending.approval.copy(status = "approved")
                // The stored arguments run unchanged; nothing from the chat can alter them now.
                addMessage(run.conversationId, "tool", toolResult(pending.toolCallId, pending.intent.summary))
                addMessage(run.conversationId, "assistant", text(pending.intent.answer))
                if (agent != null) addLog(agent.id, pending.intent, pending.intent.summary)
                run.status = "completed"
                ok(r, pending.approval)
            }
            "reject" -> {
                pending.approval = pending.approval.copy(status = "rejected")
                addMessage(run.conversationId, "assistant", text(DemoScripts.rejectedText(ToolLabels.of(pending.intent.tool))))
                if (agent != null) addLog(agent.id, pending.intent, "Rejected by you; nothing was changed")
                run.status = "completed"
                ok(r, pending.approval)
            }
            else -> throw BadRequest()
        }
    }

    // -------------------------------------------------------------------- runs

    private fun runRoute(r: Request, m: String, seg: List<String>): Response {
        val run = runs[seg.getOrNull(1)] ?: return notFound(r)
        return when {
            seg.size == 2 && m == "GET" -> ok(r, RunDto(run.id, run.status, run.errorCode))
            seg.getOrNull(2) == "cancel" && m == "POST" -> {
                run.cancelled = true
                if (run.status == "running") run.status = "cancelled"
                noContent(r)
            }
            seg.getOrNull(2) == "events" && m == "GET" -> {
                val events = Events(run)
                val chunks = when (run.status) {
                    "completed" -> listOf(events.chunk(0, "run.completed", buildJsonObject { put("message_id", "msg-${run.id}") }))
                    "failed" -> listOf(events.chunk(0, "run.error", buildJsonObject { put("code", run.errorCode ?: "unknown") }))
                    else -> emptyList()
                }
                Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ScriptedSource(chunks) { false }.buffer().asResponseBody("text/event-stream".toMediaType(), -1L)).build()
            }
            else -> notFound(r)
        }
    }

    // -------------------------------------------------------------------- logs

    private fun listLogs(r: Request, agentId: String): Response {
        val before = r.url.queryParameter("before")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val limit = r.url.queryParameter("limit")?.toIntOrNull()?.coerceIn(1, 100) ?: 30
        val list = logs.filter { it.agentId == agentId }
            .filter { before == null || Instant.parse(it.createdAt).isBefore(before) }
            .sortedByDescending { it.createdAt }.take(limit)
        return ok(r, list)
    }

    private fun addLog(agentId: String, intent: Intent, summary: String) {
        // Secrets and message bodies are redacted before they reach the audit log, as on the real server.
        val redacted = JsonObject(intent.args.mapValues { (k, v) -> if (k == "body") JsonPrimitive("[redacted]") else v })
        logs += LogEntryDto("log-${ids.incrementAndGet()}", agentId, intent.tool, redacted, summary, now().toString())
    }

    // ------------------------------------------------------------ llm profiles

    private fun llm(r: Request, m: String, seg: List<String>): Response {
        val id = seg.getOrNull(1)
        return when {
            seg.size == 1 && m == "GET" -> ok(r, profiles.values.toList())
            seg.size == 1 && m == "POST" -> saveProfile(r, null)
            id == "test" && m == "POST" -> {
                val req = body<LlmProfileRequest>(r)
                testKey(r, req.apiKey, req.model)
            }
            seg.size == 2 && m == "PATCH" -> saveProfile(r, id)
            seg.size == 2 && m == "DELETE" -> if (profiles.remove(id) != null) { profileKeys.remove(id); noContent(r) } else notFound(r)
            seg.getOrNull(2) == "test" && m == "POST" -> if (profiles[id] == null) notFound(r) else testKey(r, profileKeys[id], profiles[id]!!.model)
            else -> notFound(r)
        }
    }

    private fun testKey(r: Request, key: String?, model: String): Response = when {
        key.isNullOrBlank() || key.endsWith("bad") -> error(r, 422, "llm_key_invalid")
        model.contains("unsupported") -> ok(r, TestResultDto(false))
        else -> ok(r, TestResultDto(true))
    }

    private fun saveProfile(r: Request, id: String?): Response {
        val req = body<LlmProfileRequest>(r)
        if (req.provider !in setOf("openai", "anthropic", "openai_compatible")) throw BadRequest()
        val existing = id?.let { profiles[it] ?: return notFound(r) }
        val newId = id ?: "llm-${ids.incrementAndGet()}"
        val key = req.apiKey ?: profileKeys[newId]
        // A key that fails validation is never stored (the demo treats keys ending in "bad" as invalid).
        if (key.isNullOrBlank() || key.endsWith("bad")) return error(r, 422, "llm_key_invalid")
        profileKeys[newId] = key
        val makeDefault = req.isDefault || profiles.isEmpty()
        if (makeDefault) profiles.keys.toList().forEach { k -> profiles[k] = profiles[k]!!.copy(isDefault = false) }
        val dto = LlmProfileDto(
            newId, req.name, req.provider, req.baseUrl, req.model, req.temperature, req.maxTokens, key.takeLast(4),
            if (makeDefault) true else existing?.isDefault ?: false,
        )
        profiles[newId] = dto
        return ok(r, dto, if (id == null) 201 else 200)
    }

    // ----------------------------------------------------------------- account

    private fun account(r: Request, m: String, action: String?): Response = when {
        m == "POST" && action == "export" -> ok(r, ExportDto("https://example.com/demo-export.zip"))
        m == "DELETE" && action == null -> {
            agents.clear(); conversations.clear(); messages.clear(); runs.clear(); approvals.clear(); logs.clear(); profiles.clear(); profileKeys.clear()
            noContent(r)
        }
        else -> notFound(r)
    }

    // ----------------------------------------------------------------- helpers

    private var lastTick: Instant = Instant.EPOCH

    // Strictly increasing, so records created in the same millisecond still sort in a stable order.
    private fun now(): Instant {
        val t = clock()
        lastTick = if (t.isAfter(lastTick)) t else lastTick.plusNanos(1_000)
        return lastTick
    }

    private fun scale(ms: Long): Long = (ms * speed).toLong()

    private fun pause(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun addMessage(conversationId: String, role: String, vararg blocks: JsonObject) {
        val list = messages.getOrPut(conversationId) { mutableListOf() }
        val seq = (list.maxOfOrNull { it.seq } ?: 0L) + 1
        list += MessageDto("m-${ids.incrementAndGet()}", conversationId, seq, role, JsonArray(blocks.toList()), now().toString())
    }

    private fun text(t: String) = buildJsonObject { put("type", "text"); put("text", t) }
    private fun toolCall(id: String, i: Intent) = buildJsonObject { put("type", "tool_call"); put("id", id); put("tool", i.tool); put("args", i.args) }
    private fun toolResult(id: String, summary: String) = buildJsonObject { put("type", "tool_result_summary"); put("tool_call_id", id); put("summary", summary) }

    private fun bodyText(r: Request): String = Buffer().also { r.body?.writeTo(it) }.readUtf8()
    private inline fun <reified T> body(r: Request): T = json.decodeFromString(bodyText(r))

    private inline fun <reified T> ok(r: Request, value: T, code: Int = 200): Response =
        plain(r, code, json.encodeToString(value))

    private fun noContent(r: Request) = Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(204).message("No Content")
        .body("".toResponseBody(null)).build()

    private fun notFound(r: Request) = error(r, 404, "not_found")

    private fun error(r: Request, code: Int, errorCode: String) = plain(r, code, """{"error":{"code":"$errorCode"}}""")

    private fun plain(r: Request, code: Int, body: String) = Response.Builder().request(r).protocol(Protocol.HTTP_1_1)
        .code(code).message(if (code in 200..299) "OK" else "Error").header("Content-Type", "application/json")
        .body(body.toResponseBody("application/json".toMediaType())).build()
}

/** Readable tool names for the demo's messages. */
internal object ToolLabels {
    fun of(tool: String): String = TemplateCatalog.all.flatMap { it.tools }.firstOrNull { it.id == tool }?.label ?: tool
}
