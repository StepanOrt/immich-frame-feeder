import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath
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

            val fetch: suspend (String, String?) -> HttpResponse = { path, _ ->
                client.get(path)
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

            val fetch: suspend (String, String?) -> HttpResponse = { path, _ ->
                client.get(path)
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
            val resultNoId = getImmichAssets({ path, _ -> clientNoId.get(path) }, "album-1")
            assertEquals(1, resultNoId.size)
            assertEquals(Pair("11111111-1111-1111-1111-111111111111", "p2"), resultNoId[0])

            // Asset with missing originalPath should be skipped, while valid asset is returned
            val noPath = """{"assets": [
                {"id": "11111111-1111-1111-1111-111111111111", "type": "IMAGE", "fileCreatedAt": "2023-01-01"},
                {"id": "22222222-2222-2222-2222-222222222222", "type": "IMAGE", "fileCreatedAt": "2023-01-02", "originalPath": "valid"}
            ]}"""
            val clientNoPath = makeClient(noPath)
            val resultNoPath = getImmichAssets({ path, _ -> clientNoPath.get(path) }, "album-1")
            assertEquals(1, resultNoPath.size)
            assertEquals(Pair("22222222-2222-2222-2222-222222222222", "valid"), resultNoPath[0])

            // Missing fileCreatedAt should not throw and should default to empty string for sorting
            val noDate = """{"assets": [
                {"id": "11111111-1111-1111-1111-111111111111", "type": "IMAGE", "originalPath": "p1"},
                {"id": "22222222-2222-2222-2222-222222222222", "type": "IMAGE", "fileCreatedAt": "2023-01-02", "originalPath": "p2"}
            ]}"""
            val clientNoDate = makeClient(noDate)
            val resultNoDate = getImmichAssets({ path, _ -> clientNoDate.get(path) }, "album-1")
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
            val assets = getImmichAssets({ path, _ -> client.get(path) }, "album-1")
            assertEquals(1, assets.size)
            assertEquals("11111111-1111-1111-1111-111111111111", assets[0].first)
        }
    }

    @Test
    fun testResolveInputPathFallback() {
        // When file does not exist, absolute path starting with baseDir is preserved and relative path gets baseDir prefix
        val testBase = "/nonexistent_test_base"
        val absPath = "$testBase/library/user/photo.jpg"
        assertEquals(absPath, resolveInputPath(absPath, baseDir = testBase))

        val relPath = "upload/user/photo.jpg"
        assertEquals("$testBase/$relPath", resolveInputPath(relPath, baseDir = testBase))
    }

    @Test
    fun testResolveInputPathRejectsTraversal() {
        assertFailsWith<IllegalArgumentException> {
            resolveInputPath("../../etc/shadow")
        }
        assertFailsWith<IllegalArgumentException> {
            resolveInputPath("photos/../etc/passwd")
        }
    }

    @Test
    fun testResolveInputPathAcceptsLegitimateDotsAndColons() {
        val testBase = "/nonexistent_test_base"
        val pathWithDots = "upload/user/IMG_1234..jpg"
        assertEquals("$testBase/$pathWithDots", resolveInputPath(pathWithDots, baseDir = testBase))

        val pathWithColon = "upload/user/2023-01-01 12:30:00.jpg"
        assertEquals("$testBase/$pathWithColon", resolveInputPath(pathWithColon, baseDir = testBase))
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
        // Blocked when allowEmptyAlbum = false
        removeOldPictures(remoteAssets, immichAssetIds, runner, allowEmptyAlbum = false)
        assertTrue(runner.executedCommands.isEmpty(), "Empty album must block deletion by default")

        // Allowed when allowEmptyAlbum = true and allowMassDelete = true
        removeOldPictures(remoteAssets, immichAssetIds, runner, allowEmptyAlbum = true, allowMassDelete = true)
        assertEquals(1, runner.executedCommands.size, "Empty album must allow deletion when overrides are set")
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
        // Blocked when allowMassDelete = false
        removeOldPictures(remoteAssets, immichAssetIds, runner, allowMassDelete = false)
        assertTrue(runner.executedCommands.isEmpty(), "Mass delete (>50%) must be blocked by safety guard")

        // Allowed when allowMassDelete = true
        removeOldPictures(remoteAssets, immichAssetIds, runner, allowMassDelete = true)
        assertEquals(1, runner.executedCommands.size, "Mass delete must proceed when override is set")
    }

    @Test
    fun testRunJobUnreachableHostSkipped() {
        val runner = FakeProcessRunner()
        val assetUuid = "11111111-1111-1111-1111-111111111111"
        val mockJson = """{"assets": [{"id": "$assetUuid", "type": "IMAGE", "fileCreatedAt": "2023-01-01", "originalPath": "photo.jpg"}]}"""

        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(mockJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val fetch: suspend (String, String?) -> HttpResponse = { path, _ -> client.get(path) }

        val testBase = "/tmp/test_immich_unreachable"
        val testFile = "$testBase/photo.jpg".toPath()
        FileSystem.SYSTEM.createDirectories(testBase.toPath())
        FileSystem.SYSTEM.write(testFile) { writeUtf8("dummy") }

        try {
            // Host listing fails with exit code 255 (unreachable)
            runner.handler = { cmd, args ->
                if (cmd == "ssh" && args.any { it.startsWith("ls ") }) Pair(255, "Connection refused")
                else Pair(0, "")
            }

            runJob(
                fetch = fetch,
                albumId = "album-1",
                resolution = "1920x1080",
                quality = "90%",
                frameoHosts = listOf("frameo-offline"),
                processRunner = runner,
                baseDir = testBase
            )

            // Only the ls command should have been attempted, no convert or scp
            assertEquals(1, runner.executedCommands.size)
            assertEquals("ssh", runner.executedCommands[0].first)
            assertTrue(runner.executedCommands[0].second.any { it.contains("ls /data/data") })
        } finally {
            FileSystem.SYSTEM.delete(testFile, mustExist = false)
            FileSystem.SYSTEM.delete(testBase.toPath(), mustExist = false)
        }
    }

    @Test
    fun testRunJobSkipsExistingAssetsOnFrame() {
        val runner = FakeProcessRunner()
        val assetUuid = "11111111-1111-1111-1111-111111111111"
        val mockJson = """{"assets": [{"id": "$assetUuid", "type": "IMAGE", "fileCreatedAt": "2023-01-01", "originalPath": "photo.jpg"}]}"""

        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(mockJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val fetch: suspend (String, String?) -> HttpResponse = { path, _ -> client.get(path) }

        val testBase = "/tmp/test_immich_existing"
        val testFile = "$testBase/photo.jpg".toPath()
        FileSystem.SYSTEM.createDirectories(testBase.toPath())
        FileSystem.SYSTEM.write(testFile) { writeUtf8("dummy") }

        try {
            runner.handler = { cmd, args ->
                if (cmd == "ssh" && args.any { it.startsWith("ls ") }) {
                    Pair(0, "$assetUuid.jpg\n")
                } else {
                    Pair(0, "")
                }
            }

            runJob(
                fetch = fetch,
                albumId = "album-1",
                resolution = "1920x1080",
                quality = "90%",
                frameoHosts = listOf("frameo1"),
                processRunner = runner,
                baseDir = testBase
            )

            // Only the ls command should have been attempted; since photo exists on host, no convert or scp
            assertEquals(1, runner.executedCommands.size)
            assertEquals("ssh", runner.executedCommands[0].first)
            assertTrue(runner.executedCommands[0].second.any { it.contains("ls /data/data") })
        } finally {
            FileSystem.SYSTEM.delete(testFile, mustExist = false)
            FileSystem.SYSTEM.delete(testBase.toPath(), mustExist = false)
        }
    }

    @Test
    fun testRunJobPushesMissingAssetFullPipeline() {
        val runner = FakeProcessRunner()
        val assetUuid = "11111111-1111-1111-1111-111111111111"
        val mockJson = """{"assets": [{"id": "$assetUuid", "type": "IMAGE", "fileCreatedAt": "2023-01-01", "originalPath": "photo.jpg"}]}"""

        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(mockJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val fetch: suspend (String, String?) -> HttpResponse = { path, _ -> client.get(path) }

        val testBase = "/tmp/test_immich_pipeline"
        val testFile = "$testBase/photo.jpg".toPath()
        FileSystem.SYSTEM.createDirectories(testBase.toPath())
        FileSystem.SYSTEM.write(testFile) { writeUtf8("dummy image content") }

        try {
            runner.handler = { cmd, args ->
                when {
                    cmd == "ssh" && args.any { it.startsWith("ls ") } -> Pair(0, "") // Host has no assets
                    cmd == "convert" -> {
                        // Simulate convert writing output
                        val tmpOut = args.last()
                        FileSystem.SYSTEM.write(tmpOut.toPath()) { writeUtf8("converted-jpeg") }
                        Pair(0, "")
                    }
                    cmd == "scp" -> Pair(0, "")
                    cmd == "ssh" && args.any { it.contains("mv ") } -> Pair(0, "")
                    else -> Pair(0, "")
                }
            }

            runJob(
                fetch = fetch,
                albumId = "album-1",
                resolution = "1920x1080",
                quality = "90%",
                frameoHosts = listOf("frameo1"),
                processRunner = runner,
                baseDir = testBase
            )

            // Pipeline executed: ls -> convert -> scp -> ssh mv
            val commands = runner.executedCommands.map { it.first }
            assertEquals(listOf("ssh", "convert", "scp", "ssh"), commands)

            // Check convert args
            val convertArgs = runner.executedCommands[1].second
            assertTrue(convertArgs.contains("$testBase/photo.jpg"))
            assertTrue(convertArgs.contains("1920x1080^>"))
            assertTrue(convertArgs.contains("/tmp/$assetUuid.tmp.jpg"))

            // Check scp args
            val scpArgs = runner.executedCommands[2].second
            assertTrue(scpArgs.contains("/tmp/$assetUuid.jpg"))
            assertTrue(scpArgs.any { it.contains("frameo1:/data/data/") && it.contains("$assetUuid.jpg.part") })

            // Check mv args
            val mvArgs = runner.executedCommands[3].second
            val expectedMvCmd = "mv '/data/data/com.termux/files/home/storage/dcim/$assetUuid.jpg.part' '/data/data/com.termux/files/home/storage/dcim/$assetUuid.jpg'"
            assertTrue(mvArgs.contains(expectedMvCmd))

            // Verify temporary files are cleaned up afterwards
            assertNull(FileSystem.SYSTEM.metadataOrNull("/tmp/$assetUuid.tmp.jpg".toPath()))
            assertNull(FileSystem.SYSTEM.metadataOrNull("/tmp/$assetUuid.jpg".toPath()))
        } finally {
            FileSystem.SYSTEM.delete(testFile, mustExist = false)
            FileSystem.SYSTEM.delete(testBase.toPath(), mustExist = false)
            FileSystem.SYSTEM.delete("/tmp/$assetUuid.tmp.jpg".toPath(), mustExist = false)
            FileSystem.SYSTEM.delete("/tmp/$assetUuid.jpg".toPath(), mustExist = false)
        }
    }

    @Test
    fun testRunJobConvertFailureCleansUpAndDoesNotPush() {
        val runner = FakeProcessRunner()
        val assetUuid = "11111111-1111-1111-1111-111111111111"
        val mockJson = """{"assets": [{"id": "$assetUuid", "type": "IMAGE", "fileCreatedAt": "2023-01-01", "originalPath": "photo.jpg"}]}"""

        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(mockJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val fetch: suspend (String, String?) -> HttpResponse = { path, _ -> client.get(path) }

        val testBase = "/tmp/test_immich_convert_fail"
        val testFile = "$testBase/photo.jpg".toPath()
        FileSystem.SYSTEM.createDirectories(testBase.toPath())
        FileSystem.SYSTEM.write(testFile) { writeUtf8("dummy image content") }

        try {
            runner.handler = { cmd, args ->
                when {
                    cmd == "ssh" && args.any { it.startsWith("ls ") } -> Pair(0, "")
                    cmd == "convert" -> {
                        // Simulate failed conversion leaving partial output
                        val tmpOut = args.last()
                        FileSystem.SYSTEM.write(tmpOut.toPath()) { writeUtf8("partial output") }
                        Pair(1, "OOM / convert failed")
                    }
                    else -> Pair(0, "")
                }
            }

            runJob(
                fetch = fetch,
                albumId = "album-1",
                resolution = "1920x1080",
                quality = "90%",
                frameoHosts = listOf("frameo1"),
                processRunner = runner,
                baseDir = testBase
            )

            // Should have executed ls, then convert failed, so NO scp and NO mv
            val commands = runner.executedCommands.map { it.first }
            assertEquals(listOf("ssh", "convert"), commands)

            // Verify temporary partial file was cleaned up by finally
            assertNull(FileSystem.SYSTEM.metadataOrNull("/tmp/$assetUuid.tmp.jpg".toPath()))
            assertNull(FileSystem.SYSTEM.metadataOrNull("/tmp/$assetUuid.jpg".toPath()))
        } finally {
            FileSystem.SYSTEM.delete(testFile, mustExist = false)
            FileSystem.SYSTEM.delete(testBase.toPath(), mustExist = false)
            FileSystem.SYSTEM.delete("/tmp/$assetUuid.tmp.jpg".toPath(), mustExist = false)
            FileSystem.SYSTEM.delete("/tmp/$assetUuid.jpg".toPath(), mustExist = false)
        }
    }

    @Test
    fun testRunJobInvalidPathAssetDoesNotBlockValidAssetAndDeletions() {
        val runner = FakeProcessRunner()
        val badUuid = "11111111-1111-1111-1111-111111111111"
        val goodUuid = "22222222-2222-2222-2222-222222222222"
        val keptUuid1 = "44444444-4444-4444-4444-444444444444"
        val keptUuid2 = "55555555-5555-5555-5555-555555555555"
        val oldUuid = "33333333-3333-3333-3333-333333333333"

        // Assets in album: one invalid path (traversal), one new valid, and two already on frame
        val mockJson = """{"assets": [
            {"id": "$badUuid", "type": "IMAGE", "fileCreatedAt": "2023-01-01", "originalPath": "../../bad.jpg"},
            {"id": "$goodUuid", "type": "IMAGE", "fileCreatedAt": "2023-01-02", "originalPath": "good.jpg"},
            {"id": "$keptUuid1", "type": "IMAGE", "fileCreatedAt": "2023-01-03", "originalPath": "k1.jpg"},
            {"id": "$keptUuid2", "type": "IMAGE", "fileCreatedAt": "2023-01-04", "originalPath": "k2.jpg"}
        ]}"""

        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(mockJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val fetch: suspend (String, String?) -> HttpResponse = { path, _ -> client.get(path) }

        val testBase = "/tmp/test_immich_isolation"
        val testFile = "$testBase/good.jpg".toPath()
        FileSystem.SYSTEM.createDirectories(testBase.toPath())
        FileSystem.SYSTEM.write(testFile) { writeUtf8("dummy image content") }

        try {
            runner.handler = { cmd, args ->
                when {
                    // Frame has keptUuid1, keptUuid2, and oldUuid (1 of 3 to delete -> within 50% safety guard)
                    cmd == "ssh" && args.any { it.startsWith("ls ") } -> Pair(0, "$keptUuid1.jpg\n$keptUuid2.jpg\n$oldUuid.jpg\n")
                    cmd == "convert" -> {
                        val tmpOut = args.last()
                        FileSystem.SYSTEM.write(tmpOut.toPath()) { writeUtf8("converted") }
                        Pair(0, "")
                    }
                    else -> Pair(0, "")
                }
            }

            runJob(
                fetch = fetch,
                albumId = "album-1",
                resolution = "1920x1080",
                quality = "90%",
                frameoHosts = listOf("frameo1"),
                processRunner = runner,
                baseDir = testBase
            )

            // Verify goodUuid was converted and pushed
            val convertArgs = runner.executedCommands.filter { it.first == "convert" }.map { it.second }
            assertEquals(1, convertArgs.size)
            assertTrue(convertArgs[0].contains("$testBase/good.jpg"))
            assertTrue(convertArgs[0].contains("/tmp/$goodUuid.tmp.jpg"))

            val scpArgs = runner.executedCommands.filter { it.first == "scp" }.map { it.second }
            assertEquals(1, scpArgs.size)
            assertTrue(scpArgs[0].contains("/tmp/$goodUuid.jpg"))

            // Verify removeOldPictures was executed and removed oldUuid
            val rmArgs = runner.executedCommands.filter { it.first == "ssh" && it.second.any { arg -> arg.contains("rm -f") } }
            assertEquals(1, rmArgs.size)
            assertTrue(rmArgs[0].second.any { it.contains(oldUuid) })
        } finally {
            FileSystem.SYSTEM.delete(testFile, mustExist = false)
            FileSystem.SYSTEM.delete(testBase.toPath(), mustExist = false)
            FileSystem.SYSTEM.delete("/tmp/$goodUuid.tmp.jpg".toPath(), mustExist = false)
            FileSystem.SYSTEM.delete("/tmp/$goodUuid.jpg".toPath(), mustExist = false)
        }
    }

    @Test
    fun testRunJobScpFailureDoesNotFinalizeWithMv() {
        val runner = FakeProcessRunner()
        val assetUuid = "11111111-1111-1111-1111-111111111111"
        val mockJson = """{"assets": [{"id": "$assetUuid", "type": "IMAGE", "fileCreatedAt": "2023-01-01", "originalPath": "photo.jpg"}]}"""

        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(mockJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val fetch: suspend (String, String?) -> HttpResponse = { path, _ -> client.get(path) }

        val testBase = "/tmp/test_immich_scp_fail"
        val testFile = "$testBase/photo.jpg".toPath()
        FileSystem.SYSTEM.createDirectories(testBase.toPath())
        FileSystem.SYSTEM.write(testFile) { writeUtf8("dummy image content") }

        try {
            runner.handler = { cmd, args ->
                when {
                    cmd == "ssh" && args.any { it.startsWith("ls ") } -> Pair(0, "")
                    cmd == "convert" -> {
                        val tmpOut = args.last()
                        FileSystem.SYSTEM.write(tmpOut.toPath()) { writeUtf8("converted") }
                        Pair(0, "")
                    }
                    cmd == "scp" -> Pair(1, "scp failed to connect")
                    else -> Pair(0, "")
                }
            }

            runJob(
                fetch = fetch,
                albumId = "album-1",
                resolution = "1920x1080",
                quality = "90%",
                frameoHosts = listOf("frameo1"),
                processRunner = runner,
                baseDir = testBase
            )

            // Convert was run, scp failed -> NO ssh mv command should have executed
            val commands = runner.executedCommands.map { it.first }
            assertEquals(listOf("ssh", "convert", "scp"), commands)

            // Verify temporary files cleaned up
            assertNull(FileSystem.SYSTEM.metadataOrNull("/tmp/$assetUuid.tmp.jpg".toPath()))
            assertNull(FileSystem.SYSTEM.metadataOrNull("/tmp/$assetUuid.jpg".toPath()))
        } finally {
            FileSystem.SYSTEM.delete(testFile, mustExist = false)
            FileSystem.SYSTEM.delete(testBase.toPath(), mustExist = false)
            FileSystem.SYSTEM.delete("/tmp/$assetUuid.tmp.jpg".toPath(), mustExist = false)
            FileSystem.SYSTEM.delete("/tmp/$assetUuid.jpg".toPath(), mustExist = false)
        }
    }

    @Test
    fun testRunJobPartiallyReachableHosts() {
        val runner = FakeProcessRunner()
        val assetUuid = "11111111-1111-1111-1111-111111111111"
        val mockJson = """{"assets": [{"id": "$assetUuid", "type": "IMAGE", "fileCreatedAt": "2023-01-01", "originalPath": "photo.jpg"}]}"""

        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(mockJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val fetch: suspend (String, String?) -> HttpResponse = { path, _ -> client.get(path) }

        val testBase = "/tmp/test_immich_partial_hosts"
        val testFile = "$testBase/photo.jpg".toPath()
        FileSystem.SYSTEM.createDirectories(testBase.toPath())
        FileSystem.SYSTEM.write(testFile) { writeUtf8("dummy image content") }

        try {
            runner.handler = { cmd, args ->
                when {
                    cmd == "ssh" && args.contains("frameo-offline") -> Pair(255, "Connection timeout")
                    cmd == "ssh" && args.contains("frameo-online") && args.any { it.startsWith("ls ") } -> Pair(0, "")
                    cmd == "convert" -> {
                        val tmpOut = args.last()
                        FileSystem.SYSTEM.write(tmpOut.toPath()) { writeUtf8("converted") }
                        Pair(0, "")
                    }
                    else -> Pair(0, "")
                }
            }

            runJob(
                fetch = fetch,
                albumId = "album-1",
                resolution = "1920x1080",
                quality = "90%",
                frameoHosts = listOf("frameo-online", "frameo-offline"),
                processRunner = runner,
                baseDir = testBase
            )

            // scp and mv should only target frameo-online
            val scpCommands = runner.executedCommands.filter { it.first == "scp" }
            assertEquals(1, scpCommands.size)
            assertTrue(scpCommands[0].second.any { it.startsWith("frameo-online:") })

            val mvCommands = runner.executedCommands.filter { it.first == "ssh" && it.second.any { arg -> arg.contains("mv ") } }
            assertEquals(1, mvCommands.size)
            assertTrue(mvCommands[0].second.contains("frameo-online"))
        } finally {
            FileSystem.SYSTEM.delete(testFile, mustExist = false)
            FileSystem.SYSTEM.delete(testBase.toPath(), mustExist = false)
            FileSystem.SYSTEM.delete("/tmp/$assetUuid.tmp.jpg".toPath(), mustExist = false)
            FileSystem.SYSTEM.delete("/tmp/$assetUuid.jpg".toPath(), mustExist = false)
        }
    }

    @Test
    fun testRunJobCleansUpStalePartFiles() {
        val runner = FakeProcessRunner()
        val assetUuid = "11111111-1111-1111-1111-111111111111"
        val staleUuid = "22222222-2222-2222-2222-222222222222"
        val mockJson = """{"assets": [{"id": "$assetUuid", "type": "IMAGE", "fileCreatedAt": "2023-01-01", "originalPath": "photo.jpg"}]}"""

        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(mockJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        val fetch: suspend (String, String?) -> HttpResponse = { path, _ -> client.get(path) }

        runner.handler = { cmd, args ->
            when {
                cmd == "ssh" && args.any { it.startsWith("ls ") } -> {
                    // Host has assetUuid.jpg already and a stale .part file matching staleUuid
                    Pair(0, "$assetUuid.jpg\n$staleUuid.jpg.part\n")
                }
                else -> Pair(0, "")
            }
        }

        runJob(
            fetch = fetch,
            albumId = "album-1",
            resolution = "1920x1080",
            quality = "90%",
            frameoHosts = listOf("frameo1"),
            processRunner = runner
        )

        // Verify that rm -f was invoked for the stale .part file
        val rmPartCommands = runner.executedCommands.filter { it.first == "ssh" && it.second.any { arg -> arg.contains("$staleUuid.jpg.part") } }
        assertEquals(1, rmPartCommands.size)
        val expectedRmCmd = "rm -f /data/data/com.termux/files/home/storage/dcim/$staleUuid.jpg.part"
        assertTrue(rmPartCommands[0].second.contains(expectedRmCmd))
    }
}

class FakeProcessRunner : ProcessRunner {
    val executedCommands = mutableListOf<Pair<String, List<String>>>()
    var defaultExitCode: Int = 0
    var defaultOutput: String = ""
    var handler: ((command: String, args: List<String>) -> Pair<Int, String>)? = null

    override fun run(command: String, args: List<String>): Int {
        executedCommands.add(command to args)
        return handler?.invoke(command, args)?.first ?: defaultExitCode
    }

    override fun runWithOutput(command: String, args: List<String>): Pair<Int, String> {
        executedCommands.add(command to args)
        return handler?.invoke(command, args) ?: Pair(defaultExitCode, defaultOutput)
    }
}
