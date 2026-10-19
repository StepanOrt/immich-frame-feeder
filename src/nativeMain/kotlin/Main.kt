import arrow.continuations.SuspendApp
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import io.ktor.client.*
import io.ktor.client.engine.curl.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.getenv
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

fun parseFrameoHosts(sshConfig: String): List<String> =
    sshConfig.lines()
        .map { it.trim() }
        .filter { it.matches(Regex("^(?i)Host\\s+.*")) }
        .flatMap { line ->
            line.split(Regex("\\s+")).drop(1)
        }
        .filter { it.startsWith("frameo") && !it.contains("*") && !it.contains("?") }

val JsonDecoder = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun env(name: String): String? = getenv(name)?.toKString()

suspend fun HttpResponse.checkSuccess(): HttpResponse {
    if (status.value !in 200..299) {
        throw IllegalStateException("Request failed: Status: $status ${bodyAsText()}")
    }
    return this
}

suspend fun HttpResponse.parseJson(): JsonElement =
    JsonDecoder.decodeFromString(bodyAsText())

fun resolveInputPath(originalPath: String, baseDir: String = "/data"): String {
    val segments = originalPath.split('/', '\\')
    if (segments.any { it == ".." }) {
        throw IllegalArgumentException("Invalid path (directory traversal): $originalPath")
    }
    val clean = originalPath.removePrefix("/")
    val candidates = listOf(
        if (originalPath.startsWith("$baseDir/")) originalPath else "$baseDir/$clean",
        "$baseDir/$clean",
        "$baseDir/${clean.removePrefix("data/")}",
        "$baseDir/upload/${clean.removePrefix("data/library/").removePrefix("library/")}",
        "$baseDir/upload/$clean",
        "$baseDir/foto/${clean.removePrefix("data/foto/").removePrefix("foto/")}"
    ).distinct()
    return candidates.firstOrNull { FileSystem.SYSTEM.metadataOrNull(it.toPath()) != null }
        ?: candidates.first()
}

val AssetIdRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
val ResolutionRegex = Regex("^\\d+x\\d+[\\^!<>]*$")

suspend fun getImmichAssets(
    fetch: suspend (path: String, body: String?) -> HttpResponse,
    albumId: String
): List<Pair<String, String>> {
    var page = 1
    val allAssets = mutableListOf<JsonElement>()

    while (true) {
        val requestBody = buildJsonObject {
            putJsonArray("albumIds") { add(albumId) }
            put("page", page)
            put("size", 1000)
        }.toString()
        val response = fetch("/api/search/metadata", requestBody)
        val json = response.checkSuccess().parseJson()

        val assets = when (val assetsElement = json.jsonObject["assets"]) {
            is JsonArray -> assetsElement
            is JsonObject -> assetsElement["items"]?.jsonArray
            else -> null
        } ?: throw IllegalStateException("No assets found")

        allAssets.addAll(assets)

        val hasNextPage = when (val assetsElement = json.jsonObject["assets"]) {
            is JsonObject -> assetsElement["nextPage"] != null && assetsElement["nextPage"] !is JsonNull
            else -> false
        }
        val isLegacyArray = json.jsonObject["assets"] is JsonArray

        if (isLegacyArray || (!hasNextPage && assets.size < 1000) || assets.isEmpty()) {
            break
        }
        page++
    }

    val images = allAssets.filter {
        it.jsonObject["type"]?.jsonPrimitive?.contentOrNull.equals("IMAGE", ignoreCase = true)
    }.sortedWith(
        compareBy {
            it.jsonObject["fileCreatedAt"]?.jsonPrimitive?.contentOrNull ?: ""
        }
    ).reversed()

    return images.mapNotNull { asset ->
        val id = asset.jsonObject["id"]?.jsonPrimitive?.contentOrNull
        if (id == null || !id.matches(AssetIdRegex)) {
            println("Skipping asset with invalid or missing UUID: $id")
            return@mapNotNull null
        }
        val originalPath = asset.jsonObject["originalPath"]?.jsonPrimitive?.contentOrNull
        if (originalPath == null) {
            println("Skipping asset $id with missing originalPath")
            return@mapNotNull null
        }
        Pair(id, originalPath)
    }
}

private val SshDefaultArgs = listOf(
    "-o", "BatchMode=yes",
    "-o", "ServerAliveInterval=15",
    "-o", "ServerAliveCountMax=3"
)

private val ScpDefaultArgs = listOf(
    "-o", "BatchMode=yes"
)

interface ProcessRunner {
    fun run(command: String, args: List<String>): Int
    fun runWithOutput(command: String, args: List<String>): Pair<Int, String>
}

object SystemProcessRunner : ProcessRunner {
    override fun run(command: String, args: List<String>): Int {
        return Command(command).args(args).stdout(Stdio.Inherit).spawn().wait()
    }

    override fun runWithOutput(command: String, args: List<String>): Pair<Int, String> {
        val child = Command(command).args(args).stdout(Stdio.Pipe).spawn()
        val output = child.waitWithOutput()
        return Pair(output.status ?: -1, output.stdout ?: "")
    }
}

fun removeOldPictures(
    remoteAssetsByHost: Map<String, List<String>>,
    immichAssetIds: List<String>,
    processRunner: ProcessRunner = SystemProcessRunner,
    allowEmptyAlbum: Boolean = env("ALLOW_EMPTY_ALBUM")?.equals("true", ignoreCase = true) == true,
    allowMassDelete: Boolean = env("ALLOW_MASS_DELETE")?.equals("true", ignoreCase = true) == true
) {
    val immichSet = immichAssetIds.toSet()

    if (immichAssetIds.isEmpty() && !allowEmptyAlbum) {
        println("Warning: Immich returned 0 assets for album. Skipping deletion to prevent accidental wipe. (Set ALLOW_EMPTY_ALBUM=true to override)")
        return
    }

    for ((host, assets) in remoteAssetsByHost) {
        val managedAssets = assets.filter { it.matches(AssetIdRegex) }
        val oldAssets = managedAssets.filter { it !in immichSet }

        if (oldAssets.isNotEmpty()) {
            if (managedAssets.isNotEmpty() && oldAssets.size > managedAssets.size / 2 && !allowMassDelete) {
                println("Warning: Deleting ${oldAssets.size} of ${managedAssets.size} assets (>50%) on $host blocked by safety guard. (Set ALLOW_MASS_DELETE=true to override)")
                continue
            }

            println("Removing ${oldAssets.size} old assets from $host")
            // Batch deletion to avoid E2BIG command line length limits
            var allSucceeded = true
            for (chunk in oldAssets.chunked(100)) {
                val filesToRemove = chunk.map { "/data/data/com.termux/files/home/storage/dcim/$it.jpg" }
                val rmCmd = "rm -f " + filesToRemove.joinToString(" ")
                val status = processRunner.run("ssh", SshDefaultArgs + listOf(host, rmCmd))
                if (status != 0) {
                    allSucceeded = false
                }
            }
            if (allSucceeded) {
                println("Removed old assets on $host")
            } else {
                println("Failed to remove some old assets on $host")
            }
        }
    }
}

fun runJob(
    fetch: suspend (String, String?) -> HttpResponse,
    albumId: String,
    resolution: String,
    quality: String,
    frameoHosts: List<String>,
    processRunner: ProcessRunner = SystemProcessRunner,
    baseDir: String = "/data"
) {
    val immichAssets = runBlocking {
        getImmichAssets(fetch, albumId)
    }

    val remoteAssetsByHost = mutableMapOf<String, List<String>>()
    for (host in frameoHosts) {
        val (status, stdout) = processRunner.runWithOutput(
            "ssh",
            SshDefaultArgs + listOf(host, "ls /data/data/com.termux/files/home/storage/dcim/")
        )
        if (status == 0) {
            val lines = stdout.split("\n")
            val assets = lines
                .filter { it.endsWith(".jpg") }
                .map { it.removeSuffix(".jpg") }
            remoteAssetsByHost[host] = assets

            val staleParts = lines
                .filter { it.endsWith(".jpg.part") }
                .map { it.removeSuffix(".jpg.part") }
                .filter { it.matches(AssetIdRegex) }
            if (staleParts.isNotEmpty()) {
                println("Cleaning up ${staleParts.size} stale .part files on $host")
                val filesToRemove = staleParts.map { "/data/data/com.termux/files/home/storage/dcim/$it.jpg.part" }
                val rmPartCmd = "rm -f " + filesToRemove.joinToString(" ")
                processRunner.run("ssh", SshDefaultArgs + listOf(host, rmPartCmd))
            }
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
        val tmpOutputPath = "/tmp/$id.tmp.jpg"
        val outputPath = "/tmp/$id.jpg"
        try {
            val inputPath = resolveInputPath(originalPath, baseDir = baseDir)
            if (FileSystem.SYSTEM.metadataOrNull(inputPath.toPath()) == null) {
                println("Input file $inputPath doesn't exist")
                continue
            }

            FileSystem.SYSTEM.delete(tmpOutputPath.toPath(), mustExist = false)
            FileSystem.SYSTEM.delete(outputPath.toPath(), mustExist = false)

            val resizeArg = when {
                resolution.endsWith(">") -> resolution
                resolution.endsWith("^") -> "${resolution}>"
                else -> "${resolution}^>"
            }
            val convertStatus = processRunner.run(
                "convert",
                listOf(inputPath, "-auto-orient", "-strip", "-resize", resizeArg, "-quality", quality, tmpOutputPath)
            )

            if (convertStatus != 0) {
                println("Failed to convert $inputPath")
                continue
            }

            FileSystem.SYSTEM.atomicMove(tmpOutputPath.toPath(), outputPath.toPath())
            println("Converted $inputPath")

            println("Pushing $outputPath to Frameo devices: $targetHosts")
            for (host in targetHosts) {
                val partPath = "/data/data/com.termux/files/home/storage/dcim/$id.jpg.part"
                val finalPath = "/data/data/com.termux/files/home/storage/dcim/$id.jpg"
                val scpStatus = processRunner.run(
                    "scp",
                    ScpDefaultArgs + listOf(outputPath, "$host:$partPath")
                )
                if (scpStatus != 0) {
                    println("Failed to push $outputPath to $host")
                } else {
                    val mvStatus = processRunner.run(
                        "ssh",
                        SshDefaultArgs + listOf(host, "mv '$partPath' '$finalPath'")
                    )
                    if (mvStatus != 0) {
                        println("Failed to finalize $id.jpg on $host")
                    }
                }
            }
        } catch (e: Exception) {
            println("Error processing asset $id ($originalPath): ${e.message}")
        } finally {
            FileSystem.SYSTEM.delete(tmpOutputPath.toPath(), mustExist = false)
            FileSystem.SYSTEM.delete(outputPath.toPath(), mustExist = false)
        }
    }

    val immichAssetIds = immichAssets.map { it.first }
    removeOldPictures(remoteAssetsByHost, immichAssetIds, processRunner)
}

fun main() = SuspendApp {
    val rawImmichUrl = env("IMMICH_URL")
    if (rawImmichUrl.isNullOrBlank()) {
        println("Error: IMMICH_URL environment variable is required")
        return@SuspendApp
    }
    val immichUrl = rawImmichUrl.trimEnd('/')
    if (!immichUrl.startsWith("https://") && !immichUrl.startsWith("http://")) {
        println("Error: IMMICH_URL must start with https:// or http://")
        return@SuspendApp
    }
    if (immichUrl.startsWith("http://")) {
        println("Warning: IMMICH_URL is using unencrypted HTTP protocol; API key will be sent in plaintext")
    }

    val immichAlbumId = env("IMMICH_ALBUM_ID")
    if (immichAlbumId.isNullOrBlank()) {
        println("Error: IMMICH_ALBUM_ID environment variable is required")
        return@SuspendApp
    }

    val immichApiKey = env("IMMICH_API_KEY")
    if (immichApiKey.isNullOrBlank()) {
        println("Error: IMMICH_API_KEY environment variable is required")
        return@SuspendApp
    }

    val resolution = env("RESOLUTION") ?: "1920x1280"
    if (!resolution.matches(ResolutionRegex)) {
        println("Error: Invalid RESOLUTION format '$resolution'. Expected format like '1920x1280'")
        return@SuspendApp
    }
    val jpegQuality = env("JPEG_QUALITY") ?: "99%"

    val home = env("HOME")
    if (home.isNullOrBlank()) {
        println("Error: HOME environment variable is not set")
        return@SuspendApp
    }
    val sshConfigPath = "$home/.ssh/config".toPath()
    if (FileSystem.SYSTEM.metadataOrNull(sshConfigPath) == null) {
        println("SSH config file not found in $sshConfigPath")
        return@SuspendApp
    }

    val sshConfig = FileSystem.SYSTEM.read(sshConfigPath) { readUtf8() }
    val frameoHosts = parseFrameoHosts(sshConfig)
    if (frameoHosts.isEmpty()) {
        println("Warning: No frameo hosts found in $sshConfigPath")
    }

    val client = HttpClient(Curl) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }

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

    val pollIntervalSeconds = (env("POLL_INTERVAL_SECONDS")?.toLongOrNull() ?: 60L).coerceAtLeast(10L)
    val pollDuration = pollIntervalSeconds.seconds

    while (true) {
        val timeMark = TimeSource.Monotonic.markNow()
        println("Running job")
        try {
            runJob(fetch, immichAlbumId, resolution, jpegQuality, frameoHosts)
        } catch (e: kotlinx.coroutines.CancellationException) {
            println("Job cancelled")
            throw e
        } catch (e: Throwable) {
            println("Error in job execution (${e::class.simpleName ?: "Throwable"}): ${e.message}")
            println(e.stackTraceToString())
        }
        val waitTime = pollDuration - timeMark.elapsedNow()
        if (waitTime.isPositive()) {
            println("Waiting for $waitTime")
            delay(waitTime)
        }
    }
}
