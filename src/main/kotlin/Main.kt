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

@OptIn(ExperimentalCoroutinesApi::class)
fun main(args: Array<String>) = runBlocking(Dispatchers.IO.limitedParallelism(5)) {
    val downloadDir = Path("./downloads").createDirectories()

    if (args.isEmpty()) {
        println("Please provide the list of video URLs as arguments")
        exitProcess(0)
    }

    println("Downloading mp3 audio for ${args.size} videos...")
    convertToMp3AndDownload(args.toList()) { index, _, title ->
        val regex = Regex("""[^a-zA-Z0-9_\-.éàèëïùö\s()\[\]]""")
        val sanitizedTitle = title.replace(regex, "_")
        downloadDir.resolve("${index + 1} - $sanitizedTitle.mp3")
    }
}

private suspend fun convertToMp3AndDownload(
    youtubeVideoUrls: List<String>,
    getPath: (index: Int, videoId: String, title: String) -> Path
) = coroutineScope {
    http.get("https://ytmp3s.nu/6ufl/")

    youtubeVideoUrls.forEachIndexed { i, videoUrl ->
        launch {
            val videoId = youtubeVideoId(videoUrl)
            try {
                http.convertToMp3(videoId = videoId, getPath = { title -> getPath(i, videoId, title) })
            } catch (e: Exception) {
                System.err.println("Conversion failed for $videoId: $e${e.cause?.let { "\n   (Caused by: $it)" }}")
            }
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

private suspend fun HttpClient.convertToMp3(videoId: String, getPath: (title: String) -> Path) {
    println("Initializing conversion for $videoId...")
    val initResponse = get("https://nu.ummn.nu/api/v1/init?p=y&23=1llum1n471&_=${Random.nextFloat()}") {
        header(HttpHeaders.Referrer, "https://ytmp3s.nu/")
    }.body<InitResponse>()

    println("Starting conversion for $videoId...")
    val convertResponse = startConversion(initResponse.convertURL, videoId)

    println("Tracking progress for $videoId...")
    val progressResponse = awaitConversion(convertResponse.progressURL)

    println("Downloading mp3 file for $videoId...")
    downloadMp3(convertResponse.downloadURL, videoId, getPath(progressResponse.title))
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
