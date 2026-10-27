package dev.agenthub.app.core.network

import dev.agenthub.app.core.network.dto.AgentDto
import dev.agenthub.app.core.network.dto.ApprovalDto
import dev.agenthub.app.core.network.dto.ConversationDto
import dev.agenthub.app.core.network.dto.CreateAgentRequest
import dev.agenthub.app.core.network.dto.DecisionRequest
import dev.agenthub.app.core.network.dto.DeviceRequest
import dev.agenthub.app.core.network.dto.ExportDto
import dev.agenthub.app.core.network.dto.LlmProfileDto
import dev.agenthub.app.core.network.dto.LlmProfileRequest
import dev.agenthub.app.core.network.dto.LogEntryDto
import dev.agenthub.app.core.network.dto.TestResultDto
import dev.agenthub.app.core.network.dto.OAuthStartDto
import dev.agenthub.app.core.network.dto.RunDto
import dev.agenthub.app.core.network.dto.MessageDto
import dev.agenthub.app.core.network.dto.UpdateAgentRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Authenticated backend API. Shapes are proposed in docs/API_CONTRACT.md. User identity is the JWT only. */
interface AgentHubApi {
    @GET("agents") suspend fun agents(): Response<List<AgentDto>>
    @POST("agents") suspend fun createAgent(@Body body: CreateAgentRequest): Response<AgentDto>
    @PATCH("agents/{id}") suspend fun updateAgent(@Path("id") id: String, @Body body: UpdateAgentRequest): Response<AgentDto>
    @DELETE("agents/{id}") suspend fun deleteAgent(@Path("id") id: String): Response<Unit>

    @GET("oauth/{connector}/start")
    suspend fun oauthStart(@Path("connector") connector: String, @Query("agent_id") agentId: String): Response<OAuthStartDto>
    @DELETE("agents/{id}/connection") suspend fun disconnect(@Path("id") id: String): Response<Unit>

    @GET("agents/{id}/conversations") suspend fun conversations(@Path("id") agentId: String): Response<List<ConversationDto>>
    @POST("agents/{id}/conversations") suspend fun createConversation(@Path("id") agentId: String): Response<ConversationDto>
    @DELETE("agents/{id}/conversations") suspend fun clearHistory(@Path("id") agentId: String): Response<Unit>
    @DELETE("conversations/{id}") suspend fun deleteConversation(@Path("id") id: String): Response<Unit>
    @GET("conversations/{id}/messages")
    suspend fun messages(@Path("id") conversationId: String, @Query("after_seq") afterSeq: Long?): Response<List<MessageDto>>

    @GET("runs/{id}") suspend fun run(@Path("id") id: String): Response<RunDto>
    @POST("runs/{id}/cancel") suspend fun cancelRun(@Path("id") id: String): Response<Unit>

    @GET("llm-profiles") suspend fun llmProfiles(): Response<List<LlmProfileDto>>
    @POST("llm-profiles") suspend fun createLlmProfile(@Body body: LlmProfileRequest): Response<LlmProfileDto>
    @PATCH("llm-profiles/{id}") suspend fun updateLlmProfile(@Path("id") id: String, @Body body: LlmProfileRequest): Response<LlmProfileDto>
    @DELETE("llm-profiles/{id}") suspend fun deleteLlmProfile(@Path("id") id: String): Response<Unit>
    @POST("llm-profiles/{id}/test") suspend fun testLlmProfile(@Path("id") id: String): Response<TestResultDto>
    @POST("llm-profiles/test") suspend fun testLlmDraft(@Body body: LlmProfileRequest): Response<TestResultDto>

    @POST("agents/kill-switch") suspend fun killSwitch(): Response<Unit>
    @POST("account/export") suspend fun exportData(): Response<ExportDto>
    @DELETE("account") suspend fun deleteAccount(): Response<Unit>

    @POST("devices") suspend fun registerDevice(@Body body: DeviceRequest): Response<Unit>
    @POST("devices/unregister") suspend fun unregisterDevice(@Body body: DeviceRequest): Response<Unit>

    @GET("approvals") suspend fun approvals(): Response<List<ApprovalDto>>
    @POST("approvals/{id}/decision") suspend fun decide(@Path("id") id: String, @Body body: DecisionRequest): Response<ApprovalDto>

    @GET("agents/{id}/logs")
    suspend fun logs(
        @Path("id") agentId: String,
        @Query("before") before: String?,
        @Query("limit") limit: Int,
    ): Response<List<LogEntryDto>>
}
