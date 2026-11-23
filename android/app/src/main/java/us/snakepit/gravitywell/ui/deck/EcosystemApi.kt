package us.snakepit.gravitywell.ui.deck

import androidx.annotation.Keep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import us.snakepit.gravitywell.net.NetworkModule

/**
 * Retrofit contract for `/api/ecosystem/...` routes, sharing [NetworkModule.createRetrofit]
 * auth and error normalization (SPEC.md D12, D14).
 */
@Keep
interface EcosystemApi {
    @GET("api/ecosystem/models")
    suspend fun listModels(): EcosystemModelsResponseDto

    @PUT("api/ecosystem/models/default")
    suspend fun setDefaultModel(
        @Body body: SetDefaultModelRequestDto,
    ): SetDefaultModelResponseDto

    @GET("api/ecosystem/agents")
    suspend fun listAgents(): AgentsListResponseDto

    @POST("api/ecosystem/agents")
    suspend fun upsertAgent(
        @Body body: UpsertAgentRequestDto,
    ): AgentItemDto

    @DELETE("api/ecosystem/agents/{name}")
    suspend fun deleteAgent(
        @Path("name") name: String,
    ): DeleteAgentResponseDto

    @GET("api/ecosystem/mcp")
    suspend fun listMcpServers(): McpServersListResponseDto

    @POST("api/ecosystem/mcp")
    suspend fun addMcpServer(
        @Body body: CreateMcpServerRequestDto,
    ): McpServerItemDto

    @POST("api/ecosystem/mcp/{name}/enable")
    suspend fun enableMcpServer(
        @Path("name") name: String,
    ): ToggleMcpServerResponseDto

    @POST("api/ecosystem/mcp/{name}/disable")
    suspend fun disableMcpServer(
        @Path("name") name: String,
    ): ToggleMcpServerResponseDto

    @DELETE("api/ecosystem/mcp/{name}")
    suspend fun removeMcpServer(
        @Path("name") name: String,
    ): DeleteMcpServerResponseDto

    @GET("api/ecosystem/plugins")
    suspend fun listPlugins(): PluginsListResponseDto

    @POST("api/ecosystem/plugins/validate")
    suspend fun validatePlugin(
        @Body body: ValidatePluginRequestDto,
    ): ValidatePluginResponseDto

    @POST("api/ecosystem/plugins/install")
    suspend fun installPlugin(
        @Body body: InstallPluginRequestDto,
    ): InstallPluginResponseDto

    @POST("api/ecosystem/plugins/{name}/enable")
    suspend fun enablePlugin(
        @Path("name") name: String,
    ): TogglePluginResponseDto

    @POST("api/ecosystem/plugins/{name}/disable")
    suspend fun disablePlugin(
        @Path("name") name: String,
    ): TogglePluginResponseDto

    @DELETE("api/ecosystem/plugins/{name}")
    suspend fun uninstallPlugin(
        @Path("name") name: String,
    ): DeletePluginResponseDto

    @GET("api/ecosystem/settings")
    suspend fun getSettings(): EcosystemSettingsResponseDto

    @PATCH("api/ecosystem/settings")
    suspend fun patchSettings(
        @Body body: JsonObject,
    ): EcosystemSettingsResponseDto

    companion object {
        fun create(
            baseUrl: HttpUrl,
            okHttpClient: OkHttpClient,
            json: Json = NetworkModule.defaultJson,
        ): EcosystemApi =
            NetworkModule.createRetrofit(baseUrl, okHttpClient, json)
                .create(EcosystemApi::class.java)
    }
}
