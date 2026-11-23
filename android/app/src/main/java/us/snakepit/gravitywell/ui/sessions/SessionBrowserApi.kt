package us.snakepit.gravitywell.ui.sessions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import us.snakepit.gravitywell.net.NetworkModule
import us.snakepit.gravitywell.net.SessionSnapshotDto

@Serializable
data class ForkSessionRequestDto(
    val cwd: String? = null,
)

@Serializable
data class HostVitalsSummaryDto(
    @SerialName("cpu_percent") val cpuPercent: Double = 0.0,
    @SerialName("load_avg_1m") val loadAvg1m: Double = 0.0,
    @SerialName("load_avg_5m") val loadAvg5m: Double = 0.0,
    @SerialName("ram_used_mb") val ramUsedMb: Long = 0L,
    @SerialName("ram_total_mb") val ramTotalMb: Long = 0L,
    @SerialName("disk_used_gb") val diskUsedGb: Double = 0.0,
    @SerialName("disk_total_gb") val diskTotalGb: Double = 0.0,
)

@Serializable
data class ExternalSessionPresenceDto(
    @SerialName("session_id") val sessionId: String,
    val title: String = "External Session",
    val cwd: String? = null,
    val supervised: Boolean = false,
    val pid: Int? = null,
    @SerialName("lock_file") val lockFile: String = "",
)

@Serializable
data class ObservatoryStatusSummaryDto(
    val host: HostVitalsSummaryDto = HostVitalsSummaryDto(),
    @SerialName("external_sessions") val externalSessions: List<ExternalSessionPresenceDto> = emptyList(),
    @SerialName("active_external_count") val activeExternalCount: Int = 0,
    @SerialName("stale_lock_count") val staleLockCount: Int = 0,
    @SerialName("checked_at") val checkedAt: String = "",
)

@Serializable
data class WorkspaceFileItemDto(
    val path: String,
    val root: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
)

@Serializable
data class WorkspaceFilesResponseDto(
    val items: List<WorkspaceFileItemDto> = emptyList(),
)

@Serializable
data class ArtifactItemDto(
    val path: String,
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    @SerialName("mime_type") val mimeType: String = "application/octet-stream",
    @SerialName("modified_at") val modifiedAt: String = "",
)

@Serializable
data class ArtifactsListResponseDto(
    @SerialName("session_id") val sessionId: String = "",
    val items: List<ArtifactItemDto> = emptyList(),
)

@Serializable
data class AttachmentUploadResponseDto(
    @SerialName("attachment_id") val attachmentId: String,
    val filename: String = "",
    @SerialName("mime_type") val mimeType: String = "application/octet-stream",
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    val sha256: String = "",
)

@Serializable
data class PermissionRuleDto(
    @SerialName("rule_id") val ruleId: String,
    @SerialName("workspace_root") val workspaceRoot: String = "",
    @SerialName("tool_kind") val toolKind: String = "",
    @SerialName("command_prefix") val commandPrefix: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class PermissionRulesListResponseDto(
    val items: List<PermissionRuleDto> = emptyList(),
)

@Serializable
data class RevokePermissionRuleResponseDto(
    val revoked: Boolean = false,
    @SerialName("rule_id") val ruleId: String = "",
)

data class DownloadedArtifactPayload(
    val bytes: ByteArray,
    val mediaType: String,
)

/**
 * Supplementary Retrofit endpoints for Session Browser & Live Session Console,
 * built atop NetworkModule.createRetrofit so AuthHeaderInterceptor and
 * GatewayErrorInterceptor share one exact OkHttp pipeline (D14).
 */
interface SessionSupplementaryApi {
    @POST("api/sessions/{sessionId}/fork")
    suspend fun forkSession(
        @Path("sessionId") sessionId: String,
        @Body body: ForkSessionRequestDto = ForkSessionRequestDto(),
    ): SessionSnapshotDto

    @GET("api/observatory/status")
    suspend fun getObservatoryStatus(): ObservatoryStatusSummaryDto

    @GET("api/sessions/{sessionId}/files")
    suspend fun searchWorkspaceFiles(
        @Path("sessionId") sessionId: String,
        @Query("q") query: String = "",
        @Query("limit") limit: Int = 30,
    ): WorkspaceFilesResponseDto

    @GET("api/sessions/{sessionId}/artifacts")
    suspend fun listSessionArtifacts(
        @Path("sessionId") sessionId: String,
    ): ArtifactsListResponseDto

    @Multipart
    @POST("api/sessions/{sessionId}/attachments")
    suspend fun uploadAttachment(
        @Path("sessionId") sessionId: String,
        @Part file: MultipartBody.Part,
    ): AttachmentUploadResponseDto

    @GET("api/permissions/rules")
    suspend fun listPermissionRules(
        @Query("workspace") workspace: String? = null,
    ): PermissionRulesListResponseDto

    @DELETE("api/permissions/rules/{ruleId}")
    suspend fun revokePermissionRule(
        @Path("ruleId") ruleId: String,
    ): RevokePermissionRuleResponseDto

    companion object {
        fun create(
            baseUrl: HttpUrl,
            okHttpClient: OkHttpClient,
            json: Json = NetworkModule.defaultJson,
        ): SessionSupplementaryApi =
            NetworkModule.createRetrofit(baseUrl, okHttpClient, json)
                .create(SessionSupplementaryApi::class.java)
    }
}

/**
 * Downloads a single artifact from GET /api/sessions/{sessionId}/artifacts/{artifactPath}
 * through the shared authenticated OkHttpClient (preserving slash segments in artifactPath).
 */
suspend fun downloadBrainArtifact(
    baseUrl: HttpUrl,
    okHttpClient: OkHttpClient,
    sessionId: String,
    artifactPath: String,
    raw: Boolean = false,
): DownloadedArtifactPayload = withContext(Dispatchers.IO) {
    val normalizedBase = if (baseUrl.encodedPath.endsWith("/")) {
        baseUrl
    } else {
        baseUrl.newBuilder().encodedPath("${baseUrl.encodedPath}/").build()
    }
    val urlBuilder = normalizedBase.newBuilder()
        .addPathSegment("api")
        .addPathSegment("sessions")
        .addPathSegment(sessionId)
        .addPathSegment("artifacts")
    for (segment in artifactPath.split('/').filter { it.isNotEmpty() }) {
        urlBuilder.addPathSegment(segment)
    }
    if (raw) {
        urlBuilder.addQueryParameter("raw", "1")
    }
    val req = Request.Builder()
        .url(urlBuilder.build())
        .get()
        .build()
    okHttpClient.newCall(req).execute().use { resp ->
        val bytes = resp.body.bytes()
        val mediaType = resp.header("Content-Type") ?: "application/octet-stream"
        DownloadedArtifactPayload(bytes = bytes, mediaType = mediaType)
    }
}
