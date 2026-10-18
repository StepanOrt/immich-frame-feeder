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
                            "id": "asset-1",
                            "type": "IMAGE",
                            "fileCreatedAt": "2023-01-01T10:00:00Z",
                            "originalPath": "/data/library/user/asset-1.jpg"
                        },
                        {
                            "id": "asset-2",
                            "type": "VIDEO",
                            "fileCreatedAt": "2023-01-05T10:00:00Z",
                            "originalPath": "/data/library/user/asset-2.mp4"
                        },
                        {
                            "id": "asset-3",
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
            assertEquals(Pair("asset-3", "/data/library/user/asset-3.jpg"), assets[0])
            assertEquals(Pair("asset-1", "/data/library/user/asset-1.jpg"), assets[1])
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
                        "id": "asset-1",
                        "type": "IMAGE",
                        "fileCreatedAt": "2023-01-01T10:00:00Z",
                        "originalPath": "upload/user/asset-1.jpg"
                    },
                    {
                        "id": "asset-2",
                        "type": "VIDEO",
                        "fileCreatedAt": "2023-01-05T10:00:00Z",
                        "originalPath": "upload/user/asset-2.mp4"
                    },
                    {
                        "id": "asset-3",
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
            assertEquals(Pair("asset-3", "upload/user/asset-3.jpg"), assets[0])
            assertEquals(Pair("asset-1", "upload/user/asset-1.jpg"), assets[1])
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

            // Missing id
            val noId = """{"assets": [{"type": "IMAGE", "fileCreatedAt": "2023-01-01", "originalPath": "p"}]}"""
            val clientNoId = makeClient(noId)
            assertFailsWith<IllegalStateException> {
                getImmichAssets({ path -> runBlocking { clientNoId.get(path) } }, "album-1")
            }

            // Missing originalPath
            val noPath = """{"assets": [{"id": "1", "type": "IMAGE", "fileCreatedAt": "2023-01-01"}]}"""
            val clientNoPath = makeClient(noPath)
            assertFailsWith<IllegalStateException> {
                getImmichAssets({ path -> runBlocking { clientNoPath.get(path) } }, "album-1")
            }

            // Missing fileCreatedAt (requires at least 2 items to trigger sorting comparison)
            val noDate = """{"assets": [
                {"id": "1", "type": "IMAGE", "originalPath": "p1"},
                {"id": "2", "type": "IMAGE", "originalPath": "p2"}
            ]}"""
            val clientNoDate = makeClient(noDate)
            assertFailsWith<IllegalStateException> {
                getImmichAssets({ path -> runBlocking { clientNoDate.get(path) } }, "album-1")
            }
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
        val immichAssetIds = listOf<String>()

        // Non-UUID files will be filtered out by AssetIdRegex so no ssh command is executed
        removeOldPictures(remoteAssets, immichAssetIds)
    }
}
