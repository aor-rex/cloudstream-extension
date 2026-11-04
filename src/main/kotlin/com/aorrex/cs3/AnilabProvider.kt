package com.aorrex.cs3

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Locale

class AnilabProvider : MainAPI() {
    override var mainUrl = "https://anilab2.amdapi.click/api"
    override var name = "Anilab"
    override val supportedTypes = setOf(TvType.Anime)
    override var lang = "en"
    override val hasMainPage = true

    private suspend fun postObj(id: String): JSONObject =
        JSONObject(app.get("$mainUrl/post?id=$id").text)

    private fun titleOf(o: JSONObject): String =
        o.optString("title").ifBlank {
            o.optString("name").ifBlank {
                o.optString("eng").ifBlank { o.optString("jpn", "Unknown") }
            }
        }

    private fun posterOf(o: JSONObject): String? =
        o.optString("poster").ifBlank { o.optString("image") }.ifBlank { null }

    private suspend fun <A, B> Iterable<A>.pmap(f: suspend (A) -> B): List<B> =
        coroutineScope {
            toList().chunked(25).flatMap { chunk ->
                chunk.map { async { f(it) } }.awaitAll()
            }
        }

    private fun premieredYear(premiered: String): Int? =
        Regex("""(19|20)\d{2}""").find(premiered)?.value?.toIntOrNull()

    private suspend fun hydrate(o: JSONObject): SearchResponse? {
        val id = o.opt("id")?.toString() ?: return null
        val poster = posterOf(o)
        val title = try {
            titleOf(postObj(id)).ifBlank { null }
        } catch (_: Exception) {
            null
        } ?: return null
        return newAnimeSearchResponse(title, id, fix = false) {
            this.posterUrl = poster
        }
    }

    private fun pickArray(o: JSONObject, vararg keys: String): JSONArray? {
        for (k in keys) {
            val a = o.optJSONArray(k)
            if (a != null && a.length() > 0) return a
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val root = JSONObject(app.get("$mainUrl/home").text)
        val sections = root.optJSONArray("sections") ?: JSONArray()
        val lists = mutableListOf<HomePageList>()
        for (i in 0 until sections.length()) {
            val s = sections.optJSONObject(i) ?: continue
            val posts = s.optJSONArray("posts") ?: continue
            val items = (0 until posts.length())
                .mapNotNull { posts.optJSONObject(it) }
                .pmap { hydrate(it) }.filterNotNull()
            if (items.isNotEmpty()) {
                lists += HomePageList(s.optString("name", "Featured"), items)
            }
        }
        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val root = JSONObject(app.get("$mainUrl/search?query=$query").text)
        val arr = pickArray(root, "posts", "results", "data") ?: JSONArray()
        return (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it) }
            .pmap { hydrate(it) }.filterNotNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast("/").substringAfter("id=")
        val o = postObj(id)
        val title = titleOf(o)
        val poster = posterOf(o)
        val plot = o.optString("overview")
            .ifBlank { o.optString("description") }
            .ifBlank { o.optString("synopsis") }.ifBlank { null }

        val episodes = mutableListOf<Episode>()
        try {
            for (ep in Kyoto.episodes(id)) {
                val srv = ep.servers.firstOrNull()?.id ?: ""
                episodes += newEpisode(url = "${ep.id}|$srv", fix = false, initializer = {
                    name = ep.name
                    episode = ep.number.toIntOrNull()
                })
            }
        } catch (_: Exception) { }
        if (episodes.isEmpty()) {
            try {
                for ((i, line) in Kyoto.probe(id).withIndex()) {
                    episodes += newEpisode(url = "probe|$i", fix = false, initializer = {
                        name = line.take(140)
                        episode = i + 1
                    })
                }
            } catch (_: Exception) { }
        }

        val recs = mutableListOf<SearchResponse>()
        val seas = o.optJSONArray("seasons") ?: JSONArray()
        for (i in 0 until seas.length()) {
            val s = seas.optJSONObject(i) ?: continue
            val sid = s.opt("id")?.toString() ?: continue
            val stitle = s.optString("title").ifBlank { continue }
            recs += newAnimeSearchResponse(stitle, sid, fix = false) {
                this.posterUrl = posterOf(s)
            }
        }
        // Fallback: anilab /post carries no episode data, so if the signed
        // call above yields nothing the list stays empty until Kyoto works.

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = o.optString("genres").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            this.year = premieredYear(o.optString("premiered"))
            this.score = Score.from10(o.opt("score")?.toString()?.toDoubleOrNull())
            if (recs.isNotEmpty()) this.recommendations = recs
            addEpisodes(com.lagradost.cloudstream3.DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (epId, srvId) = data.split("|").let {
            if (it.size >= 2) it[0] to it[1] else it[0] to ""
        }
        if (epId == "probe") return false
        val links = Kyoto.loadLinksForEpisode(epId, srvId, subtitleCallback)
        links.forEach(callback)
        return links.isNotEmpty()
    }
}

/**
 * Pure-Kotlin port of the KYOTO Player 1.3.2 request signer, from Ln8::b bytecode.
 * Signed message: HOSTPATH(no scheme/query) + "\n" + sha256hex(body, lower)
 * + "\n" + ts + "\n" + nonce + "\n132". ts/nonce plaintext headers.
 * Sig = nativeSign(msg) = double-HMAC-SHA256 (see nativeSign), lowercase hex.
 * INNER = OBF xor ((i*73+41) & 0xFF), same as libkyoto.so.
 */
object Kyoto {
    private const val APP_ID = "com.kyotoplayer"
    private const val VER = "132"
    private const val BASE = "https://api.kyotoplayer.com"
    private const val OBF_HEX = "bcfb0102a27d4623de59016fcd8a511ff459d73f1671c2d2d7f1d2115e043308"

    @Volatile private var skewSec: Long = 0L

    private val innerKey: ByteArray by lazy {
        val obf = OBF_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        ByteArray(obf.size) { i ->
            (obf[i].toInt() xor ((i * 73 + 41) and 0xFF)).toByte()
        }
    }

    private fun sha256Hex(b: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(b)
        return d.joinToString("") { "%02x".format(it) }
    }

    fun syncClock(dateHeader: String?) {
        if (dateHeader == null) return
        try {
            val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("GMT")
            fmt.isLenient = false
            val serverSec = fmt.parse(dateHeader)?.time?.div(1000) ?: return
            val localSec = System.currentTimeMillis() / 1000
            skewSec = serverSec - localSec
        } catch (_: Exception) { }
    }

    private fun nonce(): String {
        val raw = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(
            raw, android.util.Base64.NO_WRAP
        ).trimEnd('=')
    }

    /** Exact replica of nativeSign @0x134f0: double HMAC-SHA256, lowercase hex.
     *  d1 = HMAC(INNER, msg); sig = HMAC(d1, msg). */
    private fun nativeSign(msg: String): String {
        val m1 = Mac.getInstance("HmacSHA256")
        m1.init(SecretKeySpec(innerKey, "HmacSHA256"))
        val d1 = m1.doFinal(msg.toByteArray())
        val m2 = Mac.getInstance("HmacSHA256")
        m2.init(SecretKeySpec(d1, "HmacSHA256"))
        return m2.doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun hmac(key: ByteArray, data: ByteArray): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(data).joinToString("") { "%02x".format(it) }
    }

    /** Candidate sigs for the on-device probe (server 403s datacenter IPs,
     *  so only the phone can test). */
    private fun sigCandidates(msg: String): List<Pair<String, String>> {
        val mb = msg.toByteArray()
        val d1 = Mac.getInstance("HmacSHA256").also {
            it.init(SecretKeySpec(innerKey, "HmacSHA256"))
        }.doFinal(mb)
        val shaMsg = MessageDigest.getInstance("SHA-256").digest(mb)
        return listOf(
            "dbl" to hmac(d1, mb),
            "sng" to hmac(innerKey, mb),
            "hmacInnerSha" to hmac(innerKey, shaMsg),
            "hmacShaInner" to hmac(
                MessageDigest.getInstance("SHA-256").digest(innerKey), mb),
            "hmacMsg32" to hmac(mb.copyOf(32), mb),
            "dblTrailNL" to hmac(
                Mac.getInstance("HmacSHA256").also {
                    it.init(SecretKeySpec(innerKey, "HmacSHA256"))
                }.doFinal((msg + "\n").toByteArray()),
                (msg + "\n").toByteArray())
        )
    }

    private fun sigVariants(methodUpper: String): List<String> {
        val msg = "$methodUpper\n$VER"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(innerKey, "HmacSHA256"))
        val single = mac.doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }
        return listOf(nativeSign(msg), single)
    }

    suspend fun post(path: String, query: String = "", body: ByteArray = ByteArray(0)): JSONObject? {
        val ts = (System.currentTimeMillis() / 1000 + skewSec).toString()
        val n = nonce()
        // Exact Ln8::b recipe: host+path (no scheme, no query) + body-hash + ts + nonce + ver.
        val msg = "api.kyotoplayer.com/$path\n${sha256Hex(body)}\n$ts\n$n\n$VER"
        val sig = nativeSign(msg)
        val headers = mutableMapOf(
            "Accept" to "application/json",
            "os-version" to android.os.Build.VERSION.SDK_INT.toString(),
            "app-id" to APP_ID,
            "app-version" to VER,
            "device-model" to (android.os.Build.MODEL ?: "Android"),
            "X-Kyoto-Ts" to ts,
            "X-Kyoto-Nonce" to n,
            "X-Kyoto-Sig" to sig
        )
        return try {
            val res = app.post(
                BASE + path + query,
                headers = headers,
                requestBody = body.toRequestBody()
            )
            syncClock(res.headers["Date"] ?: res.headers["date"])
            JSONObject(res.text)
        } catch (_: Exception) {
            null
        }
    }

    /** DIAGNOSTIC probe: 6 sig candidates, fresh ts+nonce per attempt.
     *  One line per attempt, shown on-device. */
    suspend fun probe(postId: String): List<String> {
        val lines = mutableListOf<String>()
        val body = ByteArray(0)
        // NOTE: msg ts/nonce must match the headers; rebuild per attempt.
        var idx = 0
        // build one msg per attempt inside the loop via helper below
        for ((tag, _) in listOf("dbl" to 0, "sng" to 0, "hmacInnerSha" to 0,
                "hmacShaInner" to 0, "hmacMsg32" to 0, "dblTrailNL" to 0)) {
            idx++
            val ts = (System.currentTimeMillis() / 1000 + skewSec).toString()
            val n = nonce()
            val msg = "api.kyotoplayer.com/episodes\n${sha256Hex(body)}\n$ts\n$n\n$VER"
            val sig = sigCandidates(msg).first { it.first == tag }.second
            val headers = mutableMapOf(
                "Accept" to "application/json",
                "os-version" to android.os.Build.VERSION.SDK_INT.toString(),
                "app-id" to APP_ID,
                "app-version" to VER,
                "device-model" to (android.os.Build.MODEL ?: "Android"),
                "X-Kyoto-Ts" to ts,
                "X-Kyoto-Nonce" to n,
                "X-Kyoto-Sig" to sig
            )
            try {
                val res = app.post(
                    "$BASE/episodes?id=$postId",
                    headers = headers,
                    requestBody = body.toRequestBody()
                )
                syncClock(res.headers["Date"] ?: res.headers["date"])
                val t = res.text
                val shape = try {
                    val o = JSONObject(t)
                    "keys=" + o.keys().asSequence().joinToString(",")
                } catch (_: Exception) {
                    "non-json"
                }
                lines += "z$idx $tag => ${res.code} ${t.take(120)} [$shape]"
            } catch (e: Exception) {
                lines += "z$idx $tag => EX ${e.message?.take(80)}"
            }
        }
        return lines
    }

    /** episodes for an anilab post id. returns list of (epId, name, number, servers). */
    suspend fun episodes(postId: String): List<EpInfo> {
        val root = post("episodes", "?id=$postId") ?: return emptyList()
        var arr = root.optJSONArray("list")
        if (arr == null) {
            for (k in listOf("episodes", "data", "servers", "result", "items")) {
                arr = root.optJSONArray(k)
                if (arr != null) break
            }
        }
        if (arr == null) {
            val o = root.optJSONObject("data") ?: root.optJSONObject("result")
            if (o != null) {
                for (k in listOf("list", "episodes", "servers", "items")) {
                    arr = o.optJSONArray(k)
                    if (arr != null) break
                }
            }
        }
        if (arr == null) return emptyList()
        val out = mutableListOf<EpInfo>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val eid = e.optString("id").ifBlank { continue }
            val srv = mutableListOf<SrvInfo>()
            val sa = e.optJSONArray("servers") ?: JSONArray()
            for (j in 0 until sa.length()) {
                val s = sa.optJSONObject(j) ?: continue
                val sid = s.optString("id").ifBlank { continue }
                srv += SrvInfo(sid, s.optString("name"), s.optString("lang"))
            }
            out += EpInfo(
                eid,
                e.optString("name").ifBlank { "Episode ${e.optString("number").ifBlank { (i + 1).toString() }}" },
                e.optString("number"),
                srv
            )
        }
        return out
    }

    /** stream link for a server id. */
    suspend fun stream(serverId: String, episodeId: String = ""): StreamInfo? {
        val q = if (episodeId.isNotBlank()) "?id=$serverId&episode=$episodeId" else "?id=$serverId"
        val root = post("stream", q) ?: return null
        val link = root.optString("link").ifBlank { return null }
        val subs = mutableListOf<SubtitleFile>()
        val sa = root.optJSONArray("subtitles") ?: JSONArray()
        for (i in 0 until sa.length()) {
            val s = sa.optJSONObject(i) ?: continue
            val url = s.optString("url").ifBlank { s.optString("link") }.ifBlank { continue }
            subs += SubtitleFile(s.optString("lang").ifBlank { s.optString("name") }, url)
        }
        val hdrs = mutableListOf<Pair<String, String>>()
        val va = root.optJSONArray("videoHeaders") ?: JSONArray()
        for (i in 0 until va.length()) {
            val h = va.optJSONObject(i) ?: continue
            val k = h.optString("key").ifBlank { h.optString("name") }
            val v = h.optString("value").ifBlank { h.optString("url") }
            if (k.isNotBlank() && v.isNotBlank()) hdrs += k to v
        }
        return StreamInfo(link, subs, hdrs)
    }

    suspend fun loadLinksForEpisode(
        epId: String,
        serverId: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<ExtractorLink> {
        val st = stream(serverId, epId) ?: return emptyList()
        st.subs.forEach(subtitleCallback)
        val out = mutableListOf<ExtractorLink>()
        out += newExtractorLink("Anilab", "Kyoto", st.link) {
            this.referer = ""
            this.quality = Qualities.Unknown.value
            this.headers = st.headers.toMap()
        }
        return out
    }

    data class EpInfo(val id: String, val name: String, val number: String, val servers: List<SrvInfo>)
    data class SrvInfo(val id: String, val name: String, val lang: String)
    data class StreamInfo(val link: String, val subs: List<SubtitleFile>, val headers: List<Pair<String, String>>)
}
