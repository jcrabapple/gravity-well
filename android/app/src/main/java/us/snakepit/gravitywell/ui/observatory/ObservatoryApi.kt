package us.snakepit.gravitywell.ui.observatory

import androidx.annotation.Keep
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import us.snakepit.gravitywell.net.NetworkModule

/**
 * Retrofit contract for `/api/observatory/...` endpoints, constructed atop
 * [NetworkModule.createRetrofit] so auth and [us.snakepit.gravitywell.net.GatewayErrorInterceptor]
 * share one pipeline (SPEC.md D11, D14).
 */
@Keep
interface ObservatoryApi {
    @GET("api/observatory/usage")
    suspend fun getUsage(
        @Query("refresh") refresh: Boolean = false,
    ): ObservatoryUsageResponseDto

    @GET("api/observatory/status")
    suspend fun getStatus(): ObservatoryFullStatusDto

    @GET("api/observatory/logs")
    suspend fun getLogs(
        @Query("source") source: String = "cli",
        @Query("tail") tail: Int = 200,
        @Query("q") query: String = "",
        @Query("file") file: String? = null,
    ): ObservatoryLogsResponseDto

    @GET("api/observatory/remote-control")
    suspend fun getRemoteControl(): RemoteControlStatusDto

    @POST("api/observatory/remote-control/start")
    suspend fun startRemoteControl(
        @Body body: RemoteControlStartRequestDto = RemoteControlStartRequestDto(),
    ): RemoteControlStatusDto

    @POST("api/observatory/remote-control/stop")
    suspend fun stopRemoteControl(): RemoteControlStatusDto

    @POST("api/observatory/update")
    suspend fun runUpdate(
        @Body body: RunAgyUpdateRequestDto,
    ): RunAgyUpdateResponseDto

    companion object {
        fun create(
            baseUrl: HttpUrl,
            okHttpClient: OkHttpClient,
            json: Json = NetworkModule.defaultJson,
        ): ObservatoryApi =
            NetworkModule.createRetrofit(baseUrl, okHttpClient, json)
                .create(ObservatoryApi::class.java)
    }
}
