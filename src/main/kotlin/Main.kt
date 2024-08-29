import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import java.nio.file.*
import kotlin.io.path.*
import kotlin.random.*
import kotlin.system.*
import kotlin.time.*
import kotlin.time.Duration.Companion.milliseconds

private val http = HttpClient {
    expectSuccess = true

    install(HttpCookies)
    install(ContentNegotiation) {
        json()
        serialization(ContentType.Text.Plain, DefaultJson)
    }
}

fun main(args: Array<String>) = runBlocking {
    val downloadDir = Path("./downloaded-mp3s").createDirectories()

    if (args.isEmpty()) {
        println("Please provide the list of video URLs as arguments")
        exitProcess(0)
    }

    println("Downloading mp3 audio for ${args.size} videos...")
    http.get("https://ytmp3s.nu/6ufl/")
    args.forEach { videoUrl ->
        launch {
            val videoId = youtubeVideoId(videoUrl)
            http.convertToMp3(videoId = videoId, path = downloadDir.resolve("$videoId.mp3"))
        }
    }
}

// https://youtube.com/watch?v=-4E2-0sxVUM&list=PL8dPuuaLjXtNlUrzyH5r6jN9ulIgZBpdo&index=36&pp=iAQB
private fun youtubeVideoId(url: String): String =
    Url(url).parameters["v"] ?: error("Missing 'v' query parameter in $url")

@Serializable
data class InitResponse(val convertURL: String, val error: Int)

@Serializable
data class ConvertResponse(
    val downloadURL: String,
    val progressURL: String,
    val error: Int,
    val redirect: Int = 0,
    val redirectURL: String = "",
)

@Serializable
data class ProgressResponse(
    val progress: Int,
    val error: Int,
    val title: String,
)

private suspend fun HttpClient.convertToMp3(videoId: String, path: Path) {
    println("Initializing conversion for $videoId...")
    val initResponse = get("https://nu.ummn.nu/api/v1/init?p=y&23=1llum1n471&_=${Random.nextFloat()}") {
        header(HttpHeaders.Referrer, "https://ytmp3s.nu/")
    }.body<InitResponse>()

    println("Starting conversion for $videoId...")
    val convertResponse = startConversion(initResponse.convertURL, videoId)

    println("Tracking progress for $videoId...")
    awaitConversion(convertResponse.progressURL)

    println("Downloading mp3 file for $videoId...")
    downloadMp3(convertResponse.downloadURL, videoId, path)
}

private suspend fun HttpClient.startConversion(baseUrl: String, videoId: String): ConvertResponse {
    var response = startConversionOnce(baseUrl, videoId)
    while (response.redirect > 0) {
        println("Conversion redirected to ${response.redirectURL}")
        response = startConversionOnce(response.redirectURL, videoId)
    }
    return response
}

private suspend fun HttpClient.startConversionOnce(baseUrl: String, videoId: String): ConvertResponse {
    val convertUrl = buildUrl(baseUrl) {
        parameters["v"] = "https://www.youtube.com/watch?v=$videoId"
        parameters["f"] = "mp3"
        parameters["_"] = Random.nextFloat().toString()
    }
    return get(convertUrl) { header(HttpHeaders.Referrer, "https://ytmp3s.nu/") }.body<ConvertResponse>()
}

private suspend fun HttpClient.awaitConversion(
    progressUrl: String,
    pollingPeriod: Duration = 500.milliseconds,
): ProgressResponse {
    while (true) {
        val effectiveProgressUrl = buildUrl(progressUrl) {
            parameters["_"] = Random.nextFloat().toString()
        }
        val progressResponse = get(effectiveProgressUrl).body<ProgressResponse>()
        println("  conversion in progress... (${progressResponse.progress}/3)")
        if (progressResponse.progress >= 3) {
            return progressResponse
        }
        delay(pollingPeriod)
    }
}

private suspend fun HttpClient.downloadMp3(
    baseDownloadUrl: String,
    videoId: String,
    path: Path,
) {
    val downloadUrl = buildUrl(baseDownloadUrl) {
        parameters["v"] = "v=$videoId, $videoId"
        parameters["_"] = Random.nextFloat().toString()
    }
    downloadFile(downloadUrl, path)
}

private fun buildUrl(baseUrl: String, block: URLBuilder.() -> Unit): Url = URLBuilder(baseUrl).apply(block).build()

private suspend fun HttpClient.downloadFile(downloadUrl: Url, path: Path) {
    prepareGet(downloadUrl).execute { response ->
        path.outputStream().use {
            response.bodyAsChannel().copyTo(it)
        }
    }
}
