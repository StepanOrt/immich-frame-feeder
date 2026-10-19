import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class MainTest {

    @Test
    fun testParseFrameoHosts() {
        val sshConfig = """
            Host frameo-livingroom
                HostName 192.168.1.100
                User root
            
            Host github.com
                User git
            
            Host frameo-bedroom
                HostName 192.168.1.101
            
            Host my-home-server
                HostName 10.0.0.1
        """.trimIndent()

        val hosts = parseFrameoHosts(sshConfig)
        assertEquals(listOf("frameo-livingroom", "frameo-bedroom"), hosts)
    }

    @Test
    fun testParseFrameoHostsEmpty() {
        assertEquals(emptyList(), parseFrameoHosts(""))
        assertEquals(
            emptyList(),
            parseFrameoHosts("Host server1\n    HostName 1.2.3.4\nHost server2\n")
        )
    }

    @Test
    fun testParseFrameoHostsEdgeCases() {
        val sshConfig = "  Host\tframeo1\tframeo2\r\n\tHostName frameo-fake.lan\r\nHost frameo* other\r\n"
        assertEquals(listOf("frameo1", "frameo2"), parseFrameoHosts(sshConfig))
    }

    @Test
    fun testCheckSuccessValid() {
        runBlocking {
            val client = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            content = "OK",
                            status = HttpStatusCode.OK
                        )
                    }
                }
            }
            val response = client.get("http://localhost/test")
            val checked = response.checkSuccess()
            assertEquals(HttpStatusCode.OK, checked.status)
        }
    }

    @Test
    fun testCheckSuccessError() {
        runBlocking {
            val client = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            content = "Not Found",
                            status = HttpStatusCode.NotFound
                        )
                    }
                }
            }
            val response = client.get("http://localhost/test")
            val ex = assertFailsWith<IllegalStateException> {
                response.checkSuccess()
            }
            assertTrue(ex.message!!.contains("404"))
        }
    }

    @Test
    fun testParseJson() {
        runBlocking {
            val client = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            content = """{"name": "test", "count": 42}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            }
            val response = client.get("http://localhost/test")
            val json = response.parseJson()
            assertEquals("test", json.jsonObject["name"]?.jsonPrimitive?.content)
            assertEquals("42", json.jsonObject["count"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun testParseJsonInvalid() {
        runBlocking {
            val client = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            content = "not json",
                            status = HttpStatusCode.OK
                        )
                    }
                }
            }
            val response = client.get("http://localhost/test")
            assertFailsWith<SerializationException> {
                response.parseJson()
            }
        }
    }

    @Test
    fun testGetImmichAssetsV3Success() {
        runBlocking {
            val v3SearchJson = """
            {
                "assets": {
                    "count": 3,
                    "items": [
                        {
                            "id": "11111111-1111-1111-1111-111111111111",
                            "type": "IMAGE",
                            "fileCreatedAt": "2023-01-01T10:00:00Z",
                            "originalPath": "/data/library/user/asset-1.jpg"
                        },
                        {
                            "id": "22222222-2222-2222-2222-222222222222",
                            "type": "VIDEO",
                            "fileCreatedAt": "2023-01-05T10:00:00Z",
                            "originalPath": "/data/library/user/asset-2.mp4"
                        },
                        {
                            "id": "33333333-3333-3333-3333-333333333333",
                            "type": "image",
                            "fileCreatedAt": "2023-01-03T10:00:00Z",
                            "originalPath": "/data/library/user/asset-3.jpg"
                        }
                    ]
                }
            }
            """.trimIndent()

            var requestedPath = ""
            val client = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        requestedPath = request.url.encodedPath
                        when (request.url.encodedPath) {
                            "/api/search/metadata" -> respond(
                                content = v3SearchJson,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                            else -> respond(
                                content = "Not Found",
                                status = HttpStatusCode.NotFound
                            )
                        }
                    }
                }
            }

            val fetch: suspend (String, String?) -> HttpResponse = { path, body ->
                client.post(path) {
                    if (body != null) {
                        headers {
                            append(HttpHeaders.ContentType, "application/json")
                        }
                        setBody(body)
                    }
                }
            }

            val assets = getImmichAssets(fetch, "album-123")

            assertEquals("/api/search/metadata", requestedPath)
            assertEquals(2, assets.size)
            assertEquals(Pair("33333333-3333-3333-3333-333333333333", "/data/library/user/asset-3.jpg"), assets[0])
            assertEquals(Pair("11111111-1111-1111-1111-111111111111", "/data/library/user/asset-1.jpg"), assets[1])
        }
    }

    @Test
    fun testGetImmichAssetsLegacySuccess() {
        runBlocking {
            val albumJson = """
            {
                "id": "album-123",
                "albumName": "Family Frame",
                "assets": [
                    {
                        "id": "11111111-1111-1111-1111-111111111111",
                        "type": "IMAGE",
                        "fileCreatedAt": "2023-01-01T10:00:00Z",
                        "originalPath": "upload/user/asset-1.jpg"
                    },
                    {
                        "id": "22222222-2222-2222-2222-222222222222",
                        "type": "VIDEO",
                        "fileCreatedAt": "2023-01-05T10:00:00Z",
                        "originalPath": "upload/user/asset-2.mp4"
                    },
                    {
                        "id": "33333333-3333-3333-3333-333333333333",
                        "type": "image",
                        "fileCreatedAt": "2023-01-03T10:00:00Z",
                        "originalPath": "upload/user/asset-3.jpg"
                    }
                ]
            }
            """.trimIndent()

            val client = HttpClient(MockEngine) {
                engine {
                    addHandler { _ ->
                        respond(
                            content = albumJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            }

            val fetch: (String) -> HttpResponse = { path ->
                runBlocking { client.get(path) }
            }

            val assets = getImmichAssets(fetch, "album-123")

            assertEquals(2, assets.size)
            assertEquals(Pair("33333333-3333-3333-3333-333333333333", "upload/user/asset-3.jpg"), assets[0])
            assertEquals(Pair("11111111-1111-1111-1111-111111111111", "upload/user/asset-1.jpg"), assets[1])
        }
    }

    @Test
    fun testGetImmichAssetsNoAssets() {
        runBlocking {
            val emptyAlbumJson = """{"id": "album-123", "albumName": "Empty"}"""

            val client = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            content = emptyAlbumJson,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            }

            val fetch: (String) -> HttpResponse = { path ->
                runBlocking { client.get(path) }
            }

            val ex = assertFailsWith<IllegalStateException> {
                getImmichAssets(fetch, "album-123")
            }
            assertEquals("No assets found", ex.message)
        }
    }

    @Test
    fun testGetImmichAssetsMissingFields() {
        runBlocking {
            fun makeClient(json: String) = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            content = json,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            }

            // Asset with missing id should be skipped, while valid asset is returned
            val noId = """{"assets": [
                {"type": "IMAGE", "fileCreatedAt": "2023-01-01", "originalPath": "p1"},
                {"id": "11111111-1111-1111-1111-111111111111", "type": "IMAGE", "fileCreatedAt": "2023-01-02", "originalPath": "p2"}
            ]}"""
            val clientNoId = makeClient(noId)
            val resultNoId = getImmichAssets({ path -> runBlocking { clientNoId.get(path) } }, "album-1")
            assertEquals(1, resultNoId.size)
            assertEquals(Pair("11111111-1111-1111-1111-111111111111", "p2"), resultNoId[0])

            // Asset with missing originalPath should be skipped, while valid asset is returned
            val noPath = """{"assets": [
                {"id": "11111111-1111-1111-1111-111111111111", "type": "IMAGE", "fileCreatedAt": "2023-01-01"},
                {"id": "22222222-2222-2222-2222-222222222222", "type": "IMAGE", "fileCreatedAt": "2023-01-02", "originalPath": "valid"}
            ]}"""
            val clientNoPath = makeClient(noPath)
            val resultNoPath = getImmichAssets({ path -> runBlocking { clientNoPath.get(path) } }, "album-1")
            assertEquals(1, resultNoPath.size)
            assertEquals(Pair("22222222-2222-2222-2222-222222222222", "valid"), resultNoPath[0])

            // Missing fileCreatedAt should not throw and should default to empty string for sorting
            val noDate = """{"assets": [
                {"id": "11111111-1111-1111-1111-111111111111", "type": "IMAGE", "originalPath": "p1"},
                {"id": "22222222-2222-2222-2222-222222222222", "type": "IMAGE", "fileCreatedAt": "2023-01-02", "originalPath": "p2"}
            ]}"""
            val clientNoDate = makeClient(noDate)
            val resultNoDate = getImmichAssets({ path -> runBlocking { clientNoDate.get(path) } }, "album-1")
            assertEquals(2, resultNoDate.size)
        }
    }

    @Test
    fun testGetImmichAssetsInvalidUuidSkipped() {
        runBlocking {
            val json = """{"assets": [
                {"id": "not-a-uuid", "type": "IMAGE", "fileCreatedAt": "2023-01-01", "originalPath": "p1"},
                {"id": "11111111-1111-1111-1111-111111111111", "type": "IMAGE", "fileCreatedAt": "2023-01-02", "originalPath": "p2"}
            ]}"""
            val client = HttpClient(MockEngine) {
                engine {
                    addHandler {
                        respond(
                            content = json,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            }
            val assets = getImmichAssets({ path -> runBlocking { client.get(path) } }, "album-1")
            assertEquals(1, assets.size)
            assertEquals("11111111-1111-1111-1111-111111111111", assets[0].first)
        }
    }

    @Test
    fun testResolveInputPathFallback() {
        // When file does not exist, absolute path starting with baseDir is preserved and relative path gets baseDir prefix
        val absPath = "/data/library/user/photo.jpg"
        assertEquals(absPath, resolveInputPath(absPath))

        val relPath = "upload/user/photo.jpg"
        assertEquals("/data/$relPath", resolveInputPath(relPath))
    }

    @Test
    fun testResolveInputPathRejectsTraversalAndCoders() {
        assertFailsWith<IllegalArgumentException> {
            resolveInputPath("../../etc/shadow")
        }
        assertFailsWith<IllegalArgumentException> {
            resolveInputPath("text:/etc/passwd")
        }
    }

    @Test
    fun testRemoveOldPicturesNoOldPicturesDoesNothing() {
        val runner = FakeProcessRunner()
        val remoteAssets = mapOf(
            "frameo1" to listOf("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222"),
            "frameo2" to listOf("22222222-2222-2222-2222-222222222222")
        )
        val immichAssetIds = listOf("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222")

        removeOldPictures(remoteAssets, immichAssetIds, runner)
        assertTrue(runner.executedCommands.isEmpty(), "No SSH command should be executed when all assets exist")
    }

    @Test
    fun testRemoveOldPicturesIgnoresNonUuidFiles() {
        val runner = FakeProcessRunner()
        val remoteAssets = mapOf(
            "frameo1" to listOf("IMG_0001", "some_pic", "dcim_backup")
        )
        val immichAssetIds = listOf("11111111-1111-1111-1111-111111111111")

        removeOldPictures(remoteAssets, immichAssetIds, runner)
        assertTrue(runner.executedCommands.isEmpty(), "Non-UUID files must never trigger rm command")
    }

    @Test
    fun testRemoveOldPicturesDeletesExpectedUuidAsset() {
        val runner = FakeProcessRunner()
        val keptUuid = "11111111-1111-1111-1111-111111111111"
        val oldUuid = "22222222-2222-2222-2222-222222222222"
        val keptUuid2 = "33333333-3333-3333-3333-333333333333"
        val remoteAssets = mapOf(
            "frameo1" to listOf(keptUuid, oldUuid, keptUuid2)
        )
        // 1 of 3 deleted -> within 50% safety guard
        val immichAssetIds = listOf(keptUuid, keptUuid2)

        removeOldPictures(remoteAssets, immichAssetIds, runner)
        assertEquals(1, runner.executedCommands.size)
        assertEquals("ssh", runner.executedCommands[0].first)
        val args = runner.executedCommands[0].second
        assertTrue(args.contains("frameo1"))
        assertTrue(args.any { it.contains("rm -f") && it.contains(oldUuid) })
    }

    @Test
    fun testRemoveOldPicturesEmptyAlbumGuard() {
        val runner = FakeProcessRunner()
        val remoteAssets = mapOf(
            "frameo1" to listOf("11111111-1111-1111-1111-111111111111")
        )
        val immichAssetIds = emptyList<String>()
        removeOldPictures(remoteAssets, immichAssetIds, runner)
        assertTrue(runner.executedCommands.isEmpty(), "Empty album must block deletion by default")
    }

    @Test
    fun testRemoveOldPicturesMassDeleteGuard() {
        val runner = FakeProcessRunner()
        val remoteAssets = mapOf(
            "frameo1" to listOf(
                "11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222",
                "33333333-3333-3333-3333-333333333333"
            )
        )
        // Only 1 of 3 assets kept -> 2/3 deleted (>50%)
        val immichAssetIds = listOf("11111111-1111-1111-1111-111111111111")
        removeOldPictures(remoteAssets, immichAssetIds, runner)
        assertTrue(runner.executedCommands.isEmpty(), "Mass delete (>50%) must be blocked by safety guard")
    }

    @Test
    fun testRunJobUnreachableHostSkipped() {
        val runner = FakeProcessRunner()
        val assetUuid = "11111111-1111-1111-1111-111111111111"
        val mockJson = """{"assets": [{"id": "$assetUuid", "type": "IMAGE", "fileCreatedAt": "2023-01-01", "originalPath": "p1"}]}"""

        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(mockJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val fetch: suspend (String, String?) -> HttpResponse = { path, _ -> client.get(path) }

        // Host listing fails with exit code 255 (unreachable)
        runner.exitCode = 255

        runJob(
            fetch = fetch,
            albumId = "album-1",
            resolution = "1920x1080",
            quality = "90%",
            frameoHosts = listOf("frameo-offline"),
            processRunner = runner
        )

        // Only the ls command should have been attempted, no convert or scp
        assertEquals(1, runner.executedCommands.size)
        assertEquals("ssh", runner.executedCommands[0].first)
        assertTrue(runner.executedCommands[0].second.any { it.contains("ls /data/data") })
    }
}

class FakeProcessRunner : ProcessRunner {
    val executedCommands = mutableListOf<Pair<String, List<String>>>()
    var exitCode: Int = 0
    val outputByCommand = mutableMapOf<String, Pair<Int, String>>()

    override fun run(command: String, args: List<String>): Int {
        executedCommands.add(command to args)
        return exitCode
    }

    override fun runWithOutput(command: String, args: List<String>): Pair<Int, String> {
        executedCommands.add(command to args)
        val key = "$command ${args.joinToString(" ")}"
        return outputByCommand[key] ?: Pair(exitCode, "")
    }
}
