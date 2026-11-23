package us.snakepit.gravitywell.net

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

data class NetworkTimeouts(
    val connectTimeoutMs: Long = 10_000L,
    val readTimeoutMs: Long = 30_000L,
    val writeTimeoutMs: Long = 30_000L,
    val pingIntervalMs: Long = 20_000L,
)

/**
 * Shared networking factory (docs/SPEC.md D14).
 * Both app runtime and JVM MockWebServer tests MUST construct OkHttpClient and
 * GatewayApi through this module so auth injection and error mapping share one pipeline.
 */
object NetworkModule {
    val defaultJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun createOkHttpClient(
        tokenProvider: () -> String? = { null },
        timeouts: NetworkTimeouts = NetworkTimeouts(),
        json: Json = defaultJson,
        configure: (OkHttpClient.Builder) -> Unit = {},
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeouts.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeouts.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeouts.writeTimeoutMs, TimeUnit.MILLISECONDS)
            .pingInterval(timeouts.pingIntervalMs, TimeUnit.MILLISECONDS)
            .addInterceptor(AuthHeaderInterceptor(tokenProvider))
            .addInterceptor(GatewayErrorInterceptor(json))
        configure(builder)
        return builder.build()
    }

    fun createRetrofit(
        baseUrl: HttpUrl,
        okHttpClient: OkHttpClient,
        json: Json = defaultJson,
    ): Retrofit {
        val normalized = if (baseUrl.encodedPath.endsWith("/")) {
            baseUrl
        } else {
            baseUrl.newBuilder().encodedPath("${baseUrl.encodedPath}/").build()
        }
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    fun createGatewayApi(
        baseUrl: HttpUrl,
        okHttpClient: OkHttpClient,
        json: Json = defaultJson,
    ): GatewayApi = createRetrofit(baseUrl, okHttpClient, json).create(GatewayApi::class.java)
}
