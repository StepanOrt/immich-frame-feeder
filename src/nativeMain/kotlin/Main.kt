import arrow.continuations.SuspendApp
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import io.ktor.client.*
import io.ktor.client.engine.curl.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.exit
import platform.posix.getenv
import kotlin.time.DurationUnit

val PrettyPrintJson = Json {
    prettyPrint = true
}

@OptIn(ExperimentalForeignApi::class)
fun env(name: String): String? = memScoped {
    getenv(name.cstr.getPointer(this))?.toKString()
}

suspend fun HttpResponse.checkSuccess(): HttpResponse {
    if (status.value !in 200..299) {
        throw IllegalStateException("Request failed: Status: $status ${bodyAsText()}")
    }
    return this
}

suspend fun HttpResponse.parseJson(): JsonElement =
    PrettyPrintJson.decodeFromString(JsonElement.serializer(), bodyAsText())

suspend fun getImmichAssets(fetch: (String) -> HttpResponse, albumId: String): List<Pair<String, String>> {
    val response = fetch("/api/albums/$albumId")
    val json = response.checkSuccess().parseJson()
    val assets = json.jsonObject["assets"]?.jsonArray
        ?: throw IllegalStateException("No assets found")

    val images = assets.filter {
        it.jsonObject["type"]?.jsonPrimitive?.content.equals("IMAGE", ignoreCase = true)
    }.sortedWith(
        compareBy {
            it.jsonObject["fileCreatedAt"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Asset doesn't have fileCreatedAt")
        }
    ).reversed()

    return images.map {
        Pair(
            it.jsonObject["id"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Asset doesn't have id"),
            it.jsonObject["originalPath"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Asset doesn't have originalPath")
        )
    }
}

fun removeOldPictures(remoteAssetsByHost: Map<String, List<String>>, immichAssetIds: List<String>) {
    for ((host, assets) in remoteAssetsByHost) {
        val oldAssets = assets.filter { it !in immichAssetIds }
        if (oldAssets.isNotEmpty()) {
            println("Removing $oldAssets from $host")
            val filesToRemove = oldAssets.map { "/data/data/com.termux/files/home/storage/dcim/$it.jpg" }
            val rmCmd = "rm " + filesToRemove.joinToString(" ")
            val child = Command("ssh").args(host, rmCmd).stdout(Stdio.Pipe).spawn()
            if (child.wait() == 0) {
                println("Removed old assets on $host")
            } else {
                println("Failed to remove old assets on $host")
            }
        }
    }
}

fun runJob(
    fetch: (String) -> HttpResponse,
    albumId: String,
    resolution: String,
    quality: String,
    frameoHosts: List<String>
) {
    val immichAssets = runBlocking {
        getImmichAssets(fetch, albumId)
    }

    val remoteAssetsByHost: Map<String, List<String>> = frameoHosts.associateWith { host ->
        val child = Command("ssh")
            .args(host, "ls /data/data/com.termux/files/home/storage/dcim/")
            .stdout(Stdio.Pipe)
            .spawn()
        val output = child.waitWithOutput()
        if (child.wait() == 0) {
            output.stdout.split("\n")
                .filter { it.endsWith(".jpg") }
                .map { it.removeSuffix(".jpg") }
        } else {
            println("Failed to list existing assets on $host")
            emptyList()
        }
    }

    val allRemoteAssets = remoteAssetsByHost.values.flatten().toMutableSet().toList()
    val newPictures = immichAssets.filter { it.first !in allRemoteAssets }

    for ((id, originalPath) in newPictures) {
        val inputPath = "/data/$originalPath"
        if (FileSystem.SYSTEM.metadataOrNull(inputPath.toPath()) == null) {
            println("Input file $inputPath doesn't exist")
            continue
        }

        val outputPath = "/tmp/$id.jpg"
        val convertChild = Command("convert")
            .args(inputPath, "-resize", "${resolution}^", "-quality", quality, outputPath)
            .stdout(Stdio.Pipe)
            .spawn()

        if (convertChild.wait() != 0) {
            println("Failed to convert $inputPath")
            continue
        } else {
            println("Converted $inputPath")
        }

        println("Pushing $outputPath to Frameo devices")
        for (host in frameoHosts) {
            val scpChild = Command("scp")
                .args(outputPath, "$host:/data/data/com.termux/files/home/storage/dcim/")
                .stdout(Stdio.Pipe)
                .spawn()
            if (scpChild.wait() != 0) {
                println("Failed to push $outputPath to $host")
            }
        }
    }

    val immichAssetIds = immichAssets.map { it.first }
    removeOldPictures(remoteAssetsByHost, immichAssetIds)
}

fun main() = SuspendApp {
    val immichUrl = env("IMMICH_URL") ?: throw Throwable("Immich URL not found")
    val immichAlbumId = env("IMMICH_ALBUM_ID") ?: throw Throwable("Album ID not found")
    val immichApiKey = env("IMMICH_API_KEY") ?: throw Throwable("API key not found")
    val resolution = env("RESOLUTION") ?: "1920x1280"
    val jpegQuality = env("JPEG_QUALITY") ?: "99%"

    val home = env("HOME")
    val sshConfigPath = "$home/.ssh/config".toPath()
    if (FileSystem.SYSTEM.metadataOrNull(sshConfigPath) == null) {
        println("SSH config file not found in $sshConfigPath")
        return@SuspendApp
    }

    val sshConfig = FileSystem.SYSTEM.read(sshConfigPath) { readUtf8() }
    val frameoHosts = sshConfig.split("\n")
        .filter { it.startsWith("Host") }
        .map { it.split(" ").last() }
        .filter { it.startsWith("frameo") }

    val client = HttpClient(Curl)

    val fetch: (String) -> HttpResponse = { path ->
        runBlocking {
            client.get("$immichUrl$path") {
                headers {
                    append("x-api-key", immichApiKey)
                }
            }
        }
    }

    try {
        while (true) {
            val start = Clock.System.now()
            println("Running job")
            runJob(fetch, immichAlbumId, resolution, jpegQuality, frameoHosts)
            val elapsed = (Clock.System.now() - start).toLong(DurationUnit.MILLISECONDS)
            val waitTime = 60000L - elapsed
            println("Waiting for $waitTime ms")
            delay(waitTime)
        }
    } catch (e: Throwable) {
        println("Job cancelled")
        exit(0)
    }
}
