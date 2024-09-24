import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import java.nio.file.*
import kotlin.io.path.*
import kotlin.math.*
import kotlin.system.*
import kotlin.time.*
import kotlin.time.Duration.Companion.milliseconds

suspend fun main(args: Array<String>) = withContext(Dispatchers.IO.limitedParallelism(5)) {
    if (args.isEmpty()) {
        println("Missing argument: provide the path to a file with video URLs")
        exitProcess(0)
    }

    val urlsFile = Path(args[0]).absolute()
    val urls = urlsFile.readLines().filterNot { it.isBlank() }
    val downloadDir = urlsFile.parent.resolve(urlsFile.nameWithoutExtension).createDirectories()

    val http = HttpClient {
        expectSuccess = true
        install(HttpCookies)
        install(ContentNegotiation) {
            json()
            serialization(ContentType.Text.Plain, DefaultJson)
        }
    }

    println("Downloading mp3 audio for ${urls.size} videos to $downloadDir...")
    Ytmp3Client(http).convertToMp3AndDownload(urls) { index, _, title ->
        val regex = Regex("""[^a-zA-Z0-9_\-.éàèëïùö\s()\[\]]""")
        val sanitizedTitle = title.replace(regex, "_")
        downloadDir.resolve("${index + 1} - $sanitizedTitle.mp3")
    }
}

private suspend fun Ytmp3Client.convertToMp3AndDownload(
    youtubeVideoUrls: List<String>,
    getPath: (index: Int, videoId: String, title: String) -> Path,
) = coroutineScope {
    youtubeVideoUrls.forEachIndexed { i, videoUrl ->
        launch {
            val videoId = youtubeVideoId(videoUrl)
            try {
                convertToMp3(videoId = videoId, getPath = { title -> getPath(i, videoId, title) })
            } catch (e: Exception) {
                System.err.println("Conversion failed for $videoId: $e${e.cause?.let { "\n   (Caused by: $it)" } ?: ""}")
            }
        }
    }
}

// https://youtube.com/watch?v=-4E2-0sxVUM&list=PL8dPuuaLjXtNlUrzyH5r6jN9ulIgZBpdo&index=36&pp=iAQB
private fun youtubeVideoId(url: String): String =
    Url(url).parameters["v"] ?: error("Missing 'v' query parameter in $url")

private suspend fun Ytmp3Client.convertToMp3(videoId: String, getPath: (title: String) -> Path) {
    println("Initializing conversion for $videoId...")
    val initResponse = init()

    println("Starting conversion for $videoId...")
    val convertResponse = startConversion(initResponse.convertURL, videoId)

    println("Tracking progress for $videoId...")
    val progressResponse = awaitConversion(progressUrl = convertResponse.progressURL)

    println("Downloading mp3 file for $videoId...")
    download(convertResponse.downloadURL, videoId, getPath(progressResponse.title)) { bytes, total ->
        if (total != null) {
            val progressPercent = round(bytes.toDouble() / total * 100)
            println("Downloading mp3 file for $videoId... $progressPercent%")
        }
    }
}

private suspend fun Ytmp3Client.startConversion(convertUrl: String, videoId: String): ConvertResponse {
    var response = convert(convertUrl = convertUrl, videoId = videoId)
    while (response.redirect > 0) {
        response = convert(convertUrl = response.redirectURL, videoId = videoId)
    }
    return response
}

private suspend fun Ytmp3Client.awaitConversion(
    progressUrl: String,
    pollingPeriod: Duration = 500.milliseconds,
): ProgressResponse {
    while (true) {
        val progressResponse = progress(progressUrl)
        println("  conversion in progress... (${progressResponse.progress}/${ProgressResponse.MaxProgress})")
        if (progressResponse.isComplete) {
            return progressResponse
        }
        delay(pollingPeriod)
    }
}
