package kr.artchijun.mydrive

// 앱의 서버 주고받기를 실제 mydrived 에 대고 시험 (PC 에서: MYDRIVED=서버 실행 파일 경로 ./gradlew testDebugUnitTest)
//   서버 실행 파일이 없으면 건너뜀

import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random

class ApiTest {
    companion object {
        private var proc: Process? = null
        private lateinit var dir: File
        private const val PORT = 18642
        private const val URL = "http://127.0.0.1:$PORT"

        @BeforeClass @JvmStatic fun start() {
            val bin = System.getenv("MYDRIVED") ?: ""
            assumeTrue("MYDRIVED 가 없어 건너뜀", bin.isNotEmpty() && File(bin).canExecute())
            dir = Files.createTempDirectory("mdt").toFile()
            File(dir, "data/a/b").mkdirs(); File(dir, "state").mkdirs()
            File(dir, "data/a/x.txt").writeText("hello")
            File(dir, "data/a/b/y.txt").writeText("why")
            val pb = ProcessBuilder(bin).directory(dir).redirectErrorStream(true).redirectOutput(File(dir, "log"))
            pb.environment().putAll(mapOf("MYDRIVE_TOKEN" to "test", "MYDRIVE_ROOTS" to "rt=${dir}/data", "MYDRIVE_STATE" to "${dir}/state",
                "MYDRIVE_NAS_BASE" to dir.path, "MYDRIVE_LISTEN" to ":$PORT"))
            proc = pb.start()
            repeat(50) { if (Api.ping(URL, 500)) return; Thread.sleep(100) }
            fail("서버가 켜지지 않음: " + File(dir, "log").readText())
        }

        @AfterClass @JvmStatic fun stop() { proc?.destroy(); proc?.waitFor() }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    @Test fun pickSkipsDeadServer() {
        val api = Api(listOf("http://127.0.0.1:1", URL), "test")   // 앞 주소는 닫혀 있음 (집 밖에서 집 주소)
        assertEquals(URL, api.pick())
    }

    @Test fun rootsAndList() {
        val api = Api(listOf(URL), "test")
        val r = api.roots()
        assertEquals(listOf("rt"), r.map { it.name })
        assertTrue("항목 수 ${r[0].items}", r[0].items >= 4)
        assertEquals(listOf("a"), api.list("rt", "").filter { it.name == "a" }.map { it.path })
        val a = api.list("rt", "a")
        assertEquals(listOf("a/b", "a/x.txt"), a.map { it.path })
        assertTrue(a[0].dir); assertEquals(5L, a[1].size); assertTrue(a[1].mtimeNs > 0)
    }

    @Test fun wrongTokenIs401() {
        try { Api(listOf(URL), "nope").roots(); fail("401 이어야 함") } catch (e: ApiException) { assertEquals(401, e.status) }
    }

    @Test fun missingFolderIs404() {
        try { Api(listOf(URL), "test").list("rt", "a/없음"); fail("404 여야 함") } catch (e: ApiException) { assertEquals(404, e.status) }
    }

    @Test fun uploadSmallAndNoOverwrite() {
        val api = Api(listOf(URL), "test")
        val data = "작은 파일 ${Random.nextInt()}".toByteArray()
        val e = api.upload("rt", "up/작은 파일.txt", data.size.toLong(), 1_700_000_000_000_000_000, { data.inputStream() }, { _, _ -> }, { false })
        assertEquals(data.size.toLong(), e.size)
        assertArrayEquals(data, File(dir, "data/up/작은 파일.txt").readBytes())
        try { api.upload("rt", "up/작은 파일.txt", 3, 1, { "new".byteInputStream() }, { _, _ -> }, { false }); fail("같은 이름은 409") }
        catch (x: ApiException) { assertEquals(409, x.status) }
        assertArrayEquals("NAS 파일을 덮지 않음", data, File(dir, "data/up/작은 파일.txt").readBytes())
    }

    @Test fun uploadBigInParts() {
        val api = Api(listOf(URL), "test").apply { partSize = 1L * 1024 * 1024 }   // 시험: 1MB 씩
        val data = Random.nextBytes(3 * 1024 * 1024 + 777)
        var last = 0L
        val e = api.upload("rt", "up/big/큰 파일.bin", data.size.toLong(), 1_700_000_000_000_000_000, { data.inputStream() }, { d, _ -> last = d }, { false })
        assertEquals(data.size.toLong(), e.size); assertEquals(data.size.toLong(), last)
        assertArrayEquals(sha(data), sha(File(dir, "data/up/big/큰 파일.bin").readBytes()))
        assertTrue("새 폴더도 바로 목록에", api.list("rt", "up").any { it.path == "up/big" && it.dir })
        assertTrue("숨은 조각 폴더는 비어 있음", File(dir, "data/.mydrive-tmp-up").list()?.isEmpty() ?: true)
    }

    @Test fun cancelUpload() {
        val api = Api(listOf(URL), "test").apply { partSize = 1L * 1024 * 1024 }
        val data = Random.nextBytes(3 * 1024 * 1024)
        try { api.upload("rt", "up/취소.bin", data.size.toLong(), 1, { data.inputStream() }, { d, _ -> }, { true }); fail("취소되어야 함") } catch (e: Canceled) { }
        assertTrue("취소한 파일은 NAS 에 없음", !File(dir, "data/up/취소.bin").exists())
    }

    @Test fun downloadAndResume() {
        val api = Api(listOf(URL), "test")
        val data = Random.nextBytes(2 * 1024 * 1024 + 5)
        File(dir, "data/dl").mkdirs(); File(dir, "data/dl/f.bin").writeBytes(data)
        Thread.sleep(800)   // 서버 감시가 알아채게
        val e = api.list("rt", "dl").first { it.name == "f.bin" }
        val out = File(dir, "phone/f.bin"); out.parentFile.mkdirs()
        // 받다가 끊긴 상태를 흉내: 앞 1MB 만 받아 둠 → 이어서 받아야 함
        File(out.path + ".part").writeBytes(data.copyOfRange(0, 1024 * 1024))
        File(out.path + ".part.id").writeText("${e.size}:${e.mtimeNs}")
        var first = -1L
        api.download("rt", e, out, { d, _ -> if (first < 0) first = d }, { false })
        assertArrayEquals(sha(data), sha(out.readBytes()))
        assertTrue("이어 받기 (처음 진행값 $first)", first > 1024 * 1024)
        assertTrue(!File(out.path + ".part").exists())
        // 서버 파일이 바뀌었으면 받아 둔 조각을 버리고 처음부터
        File(out.path + ".part").writeBytes(ByteArray(100)); File(out.path + ".part.id").writeText("1:1")
        api.download("rt", e, out, { _, _ -> }, { false })
        assertArrayEquals(sha(data), sha(out.readBytes()))
    }

    @Test fun uniqueNames() {
        val taken = setOf("a.txt", "a (2).txt", "b")
        assertEquals("a (3).txt", Fmt.uniqueName("a.txt") { it in taken })
        assertEquals("b (2)", Fmt.uniqueName("b") { it in taken })
        assertEquals("c.txt", Fmt.uniqueName("c.txt") { it in taken })
    }

}

class UpdateTest {
    @Test fun versionOrder() {
        assertTrue(Update.newer("0.1.10", "0.1.9")); assertTrue(Update.newer("0.2", "0.1.9"))
        assertTrue(!Update.newer("0.1.0", "0.1.0")); assertTrue(!Update.newer("0.1.0", "0.1.1"))
    }

}

