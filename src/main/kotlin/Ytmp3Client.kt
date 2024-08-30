import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.content.ProgressListener
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.random.*

internal class Ytmp3Client(private val httpClient: HttpClient) {

    suspend fun init(): InitResponse =
        httpClient.get {
            url {
                takeFrom("https://nu.ummn.nu/api/v1/init?p=y&23=1llum1n471")
                parameter("_", Random.nextFloat().toString())
            }
            header(HttpHeaders.Referrer, "https://ytmp3s.nu/")
        }.body<InitResponse>().also {
            check(it.error == 0) { "Received error code ${it.error}" }
        }

    suspend fun convert(convertUrl: String, videoId: String, format: String = "mp3"): ConvertResponse =
        httpClient.get {
            url {
                takeFrom(convertUrl)
                parameter("v", "https://www.youtube.com/watch?v=$videoId")
                parameter("f", format)
                parameter("_", Random.nextFloat().toString())
            }
            header(HttpHeaders.Referrer, "https://ytmp3s.nu/")
        }.body<ConvertResponse>().also {
            check(it.error == 0) { "Received error code ${it.error}" }
        }

    suspend fun progress(progressUrl: String): ProgressResponse =
        httpClient.get {
            url {
                takeFrom(progressUrl)
                parameter("_", Random.nextFloat().toString())
            }
        }.body<ProgressResponse>().also {
            check(it.error == 0) { "Received error code ${it.error}" }
        }

    suspend fun download(downloadUrl: String, videoId: String, path: Path, progressListener: ProgressListener? = null) {
        httpClient.prepareGet {
            url {
                takeFrom(downloadUrl)
                parameter("v", "v=$videoId, $videoId")
                parameter("_", Random.nextFloat().toString())
            }
            onDownload(progressListener)
        }.execute { response ->
            path.outputStream().use {
                response.bodyAsChannel().copyTo(it)
            }
        }
    }
}

@Serializable
internal data class InitResponse(val convertURL: String, val error: Int)

@Serializable
internal data class ConvertResponse(
    val downloadURL: String,
    val progressURL: String,
    val error: Int,
    val redirect: Int = 0,
    val redirectURL: String = "",
)

@Serializable
internal data class ProgressResponse(
    val progress: Int,
    val error: Int,
    val title: String,
) {
    val isComplete = progress == MaxProgress

    companion object {
        val MaxProgress = 3
    }
}
