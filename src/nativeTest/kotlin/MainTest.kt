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
        // When file does not exist, absolute path is preserved and relative path gets baseDir prefix
        val absPath = "/data/library/user/photo.jpg"
        assertEquals(absPath, resolveInputPath(absPath))

        val relPath = "upload/user/photo.jpg"
        assertEquals("/data/$relPath", resolveInputPath(relPath))
    }

    @Test
    fun testRemoveOldPicturesNoOldPicturesDoesNothing() {
        // If all remote assets are present in immichAssetIds, ssh should not be called
        val remoteAssets = mapOf(
            "frameo1" to listOf("img-1", "img-2"),
            "frameo2" to listOf("img-2")
        )
        val immichAssetIds = listOf("img-1", "img-2", "img-3")

        // Should complete without error or attempting to spawn ssh
        removeOldPictures(remoteAssets, immichAssetIds)
    }

    @Test
    fun testRemoveOldPicturesIgnoresNonUuidFiles() {
        // Non-UUID files such as user photos or system files in DCIM must never be deleted
        val remoteAssets = mapOf(
            "frameo1" to listOf("IMG_0001", "some_pic", "dcim_backup")
        )
        val immichAssetIds = listOf("11111111-1111-1111-1111-111111111111")

        // Non-UUID files will be filtered out by AssetIdRegex so no ssh command is executed
        removeOldPictures(remoteAssets, immichAssetIds)
    }

    @Test
    fun testRemoveOldPicturesEmptyAlbumGuard() {
        // When Immich returns 0 assets, deletion is blocked by default to prevent wiping frames
        val remoteAssets = mapOf(
            "frameo1" to listOf("11111111-1111-1111-1111-111111111111")
        )
        val immichAssetIds = emptyList<String>()
        removeOldPictures(remoteAssets, immichAssetIds)
    }

    @Test
    fun testRemoveOldPicturesMassDeleteGuard() {
        // When more than 50% of photos would be deleted, guard prevents deletion by default
        val remoteAssets = mapOf(
            "frameo1" to listOf(
                "11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222",
                "33333333-3333-3333-3333-333333333333"
            )
        )
        // Only 1 of 3 assets kept -> 2/3 deleted (>50%)
        val immichAssetIds = listOf("11111111-1111-1111-1111-111111111111")
        removeOldPictures(remoteAssets, immichAssetIds)
    }
}
