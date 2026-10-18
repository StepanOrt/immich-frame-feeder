import arrow.continuations.SuspendApp
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import io.ktor.client.*
import io.ktor.client.engine.curl.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.exit
import platform.posix.getenv
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

fun parseFrameoHosts(sshConfig: String): List<String> =
    sshConfig.split("\n")
        .filter { it.startsWith("Host") }
        .map { it.split(" ").last() }
        .filter { it.startsWith("frameo") }

val PrettyPrintJson = Json {
    prettyPrint = true
}

fun env(name: String): String? = getenv(name)?.toKString()

suspend fun HttpResponse.checkSuccess(): HttpResponse {
    if (status.value !in 200..299) {
        throw IllegalStateException("Request failed: Status: $status ${bodyAsText()}")
    }
    return this
}

suspend fun HttpResponse.parseJson(): JsonElement =
    PrettyPrintJson.decodeFromString(bodyAsText())

fun resolveInputPath(originalPath: String, baseDir: String = "/data"): String {
    val clean = originalPath.removePrefix("/")
    val candidates = listOf(
        if (originalPath.startsWith("/")) originalPath else "$baseDir/$clean",
        "$baseDir/$clean",
        "$baseDir/${clean.removePrefix("data/")}",
        "$baseDir/upload/${clean.removePrefix("data/library/").removePrefix("library/")}",
        "$baseDir/upload/$clean"
    ).distinct()
    return candidates.firstOrNull { FileSystem.SYSTEM.metadataOrNull(it.toPath()) != null }
        ?: candidates.first()
}

suspend fun getImmichAssets(
    fetch: suspend (path: String, body: String?) -> HttpResponse,
    albumId: String
): List<Pair<String, String>> {
    var page = 1
    val allAssets = mutableListOf<JsonElement>()

    while (true) {
        val requestBody = """{"albumIds":["$albumId"],"page":$page,"size":1000}"""
        val response = fetch("/api/search/metadata", requestBody)
        val json = response.checkSuccess().parseJson()

        val assets = when (val assetsElement = json.jsonObject["assets"]) {
            is JsonArray -> assetsElement
            is JsonObject -> assetsElement["items"]?.jsonArray
            else -> null
        } ?: throw IllegalStateException("No assets found")

        allAssets.addAll(assets)

        if (assets.size < 1000 || json.jsonObject["assets"] is JsonArray) {
            break
        }
        page++
    }

    val images = allAssets.filter {
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

suspend fun getImmichAssets(
    fetch: (String) -> HttpResponse,
    albumId: String
): List<Pair<String, String>> = getImmichAssets({ path, _ -> fetch(path) }, albumId)


fun removeOldPictures(remoteAssetsByHost: Map<String, List<String>>, immichAssetIds: List<String>) {
    for ((host, assets) in remoteAssetsByHost) {
        val oldAssets = assets.filter { it !in immichAssetIds }
        if (oldAssets.isNotEmpty()) {
            println("Removing $oldAssets from $host")
            val filesToRemove = oldAssets.map { "/data/data/com.termux/files/home/storage/dcim/$it.jpg" }
            val rmCmd = "rm " + filesToRemove.joinToString(" ")
            val child = Command("ssh").args(listOf(host, rmCmd)).stdout(Stdio.Pipe).spawn()
            if (child.wait() == 0) {
                println("Removed old assets on $host")
            } else {
                println("Failed to remove old assets on $host")
            }
        }
    }
}

fun runJob(
    fetch: suspend (String, String?) -> HttpResponse,
    albumId: String,
    resolution: String,
    quality: String,
    frameoHosts: List<String>
) {
    val immichAssets = runBlocking {
        getImmichAssets(fetch, albumId)
    }

    val remoteAssetsByHost = mutableMapOf<String, List<String>>()
    for (host in frameoHosts) {
        val child = Command("ssh")
            .args(listOf(host, "ls /data/data/com.termux/files/home/storage/dcim/"))
            .stdout(Stdio.Pipe)
            .spawn()
        val output = child.waitWithOutput()
        if (output.status == 0) {
            val assets = output.stdout?.split("\n")
                ?.filter { it.endsWith(".jpg") }
                ?.map { it.removeSuffix(".jpg") }
                ?: emptyList()
            remoteAssetsByHost[host] = assets
        } else {
            println("Failed to list existing assets on $host; skipping host for this cycle")
        }
    }

    if (remoteAssetsByHost.isEmpty()) {
        println("No reachable Frameo hosts in this cycle")
        return
    }

    val immichAssetMap = immichAssets.toMap()
    val hostsNeedingAsset = mutableMapOf<String, MutableList<String>>() // assetId -> list of hosts
    for ((host, remoteAssets) in remoteAssetsByHost) {
        val remoteSet = remoteAssets.toSet()
        for ((id, _) in immichAssets) {
            if (id !in remoteSet) {
                hostsNeedingAsset.getOrPut(id) { mutableListOf() }.add(host)
            }
        }
    }

    for ((id, targetHosts) in hostsNeedingAsset) {
        val originalPath = immichAssetMap[id] ?: continue
        val inputPath = resolveInputPath(originalPath)
        if (FileSystem.SYSTEM.metadataOrNull(inputPath.toPath()) == null) {
            println("Input file $inputPath doesn't exist")
            continue
        }

        val outputPath = "/tmp/$id.jpg"
        if (FileSystem.SYSTEM.metadataOrNull(outputPath.toPath()) == null) {
            val convertChild = Command("convert")
                .args(listOf(inputPath, "-resize", "${resolution}^", "-quality", quality, outputPath))
                .stdout(Stdio.Pipe)
                .spawn()

            if (convertChild.wait() != 0) {
                println("Failed to convert $inputPath")
                continue
            } else {
                println("Converted $inputPath")
            }
        }

        println("Pushing $outputPath to Frameo devices: $targetHosts")
        for (host in targetHosts) {
            val scpChild = Command("scp")
                .args(listOf(outputPath, "$host:/data/data/com.termux/files/home/storage/dcim/"))
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

fun runJob(
    fetch: (String) -> HttpResponse,
    albumId: String,
    resolution: String,
    quality: String,
    frameoHosts: List<String>
): Unit = runJob({ path, _ -> fetch(path) }, albumId, resolution, quality, frameoHosts)

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
    val frameoHosts = parseFrameoHosts(sshConfig)

    val client = HttpClient(Curl)

    val fetch: suspend (String, String?) -> HttpResponse = { path, body ->
        if (body != null) {
            client.post("$immichUrl$path") {
                header("x-api-key", immichApiKey)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } else {
            client.get("$immichUrl$path") {
                header("x-api-key", immichApiKey)
            }
        }
    }

    try {
        while (true) {
            val timeMark = TimeSource.Monotonic.markNow()
            println("Running job")
            runJob(fetch, immichAlbumId, resolution, jpegQuality, frameoHosts)
            val waitTime = 1.minutes - timeMark.elapsedNow()
            if (waitTime.isPositive()) {
                println("Waiting for $waitTime")
                delay(waitTime)
            }
        }
    } catch (e: Throwable) {
        println("Job cancelled")
        exit(0)
    }
}
