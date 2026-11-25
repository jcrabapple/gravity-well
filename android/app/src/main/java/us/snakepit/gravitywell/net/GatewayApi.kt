package us.snakepit.gravitywell.net

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface GatewayApi {
    @GET("api/health")
    suspend fun getHealth(): HealthResponse

    @GET("api/info")
    suspend fun getInfo(
        @Header("Authorization") authorization: String? = null,
    ): GatewayInfoResponse

    @POST("api/ws-ticket")
    suspend fun mintWsTicket(
        @Header("Authorization") authorization: String? = null,
    ): WsTicketResponse

    @GET("api/sessions")
    suspend fun listSessions(
        @Query("status") status: String = "all",
        @Query("q") query: String? = null,
        @Query("cwd") cwd: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): SessionListResponse

    @POST("api/sessions")
    suspend fun createSession(
        @Body body: CreateSessionRequest,
    ): SessionSnapshotDto

    @GET("api/sessions/{sessionId}")
    suspend fun getSession(
        @Path("sessionId") sessionId: String,
    ): SessionSnapshotDto

    @POST("api/sessions/{sessionId}/resume")
    suspend fun resumeSession(
        @Path("sessionId") sessionId: String,
        @Body body: ResumeSessionRequest,
    ): SessionSnapshotDto

    @PATCH("api/sessions/{sessionId}")
    suspend fun renameSession(
        @Path("sessionId") sessionId: String,
        @Body body: RenameSessionRequest,
    ): SessionSnapshotDto

    @PATCH("api/sessions/{sessionId}/config")
    suspend fun updateSessionConfig(
        @Path("sessionId") sessionId: String,
        @Body body: UpdateConfigRequest,
    ): SessionSnapshotDto

    @DELETE("api/sessions/{sessionId}")
    suspend fun closeSession(
        @Path("sessionId") sessionId: String,
    ): DeleteSessionResponse

    @DELETE("api/sessions/{sessionId}")
    suspend fun deleteSessionPermanently(
        @Path("sessionId") sessionId: String,
        @Query("permanent") permanent: Boolean = true,
    ): DeleteSessionResponse

    @POST("api/sessions/{sessionId}/prompt")
    suspend fun submitPrompt(
        @Path("sessionId") sessionId: String,
        @Body body: SubmitPromptRequest,
    ): SubmitPromptResponse

    @POST("api/sessions/{sessionId}/cancel")
    suspend fun cancelTurn(
        @Path("sessionId") sessionId: String,
    ): CancelSessionResponse

    @GET("api/sessions/{sessionId}/queue")
    suspend fun getQueue(
        @Path("sessionId") sessionId: String,
    ): QueueListResponse

    @POST("api/sessions/{sessionId}/queue")
    suspend fun enqueueItem(
        @Path("sessionId") sessionId: String,
        @Body body: EnqueueRequest,
    ): QueueItemDto

    @PATCH("api/sessions/{sessionId}/queue/{itemId}")
    suspend fun editQueueItem(
        @Path("sessionId") sessionId: String,
        @Path("itemId") itemId: String,
        @Body body: EditQueueItemRequest,
    ): QueueItemDto

    @DELETE("api/sessions/{sessionId}/queue/{itemId}")
    suspend fun deleteQueueItem(
        @Path("sessionId") sessionId: String,
        @Path("itemId") itemId: String,
    )

    @POST("api/sessions/{sessionId}/queue/{itemId}/send-now")
    suspend fun sendQueueItemNow(
        @Path("sessionId") sessionId: String,
        @Path("itemId") itemId: String,
        @Body body: SendNowRequest = SendNowRequest(),
    )

    @POST("api/sessions/{sessionId}/permissions/{requestId}")
    suspend fun resolvePermission(
        @Path("sessionId") sessionId: String,
        @Path("requestId") requestId: String,
        @Body body: ResolvePermissionRequest,
    )

    @POST("api/sessions/{sessionId}/questions/{requestId}")
    suspend fun resolveQuestion(
        @Path("sessionId") sessionId: String,
        @Path("requestId") requestId: String,
        @Body body: ResolveQuestionRequest,
    )

    @GET("api/sessions/{sessionId}/events")
    suspend fun getSessionEvents(
        @Path("sessionId") sessionId: String,
        @Query("after") after: Long = 0L,
        @Query("limit") limit: Int = 500,
    ): SessionEventsResponse
}
