package com.aorrex.cs3

import android.util.Base64
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
        val epArr = pickArray(o, "episodes", "episodeList", "eps")
        if (epArr != null) {
            for (i in 0 until epArr.length()) {
                val e = epArr.optJSONObject(i) ?: continue
                val num = e.opt("number")?.toString()
                    ?: e.opt("episode")?.toString()
                    ?: (i + 1).toString()
                episodes += newEpisode(url = "$id|$num", fix = false, initializer = {
                    name = "Episode $num"
                    episode = num.toIntOrNull()
                })
            }
        } else {
            val total = o.optInt("totalEpisodes",
                o.optInt("episodesCount", o.optInt("episodes", 0)))
            for (n in 1..total) {
                episodes += newEpisode(url = "$id|$n", fix = false, initializer = {
                    name = "Episode $n"
                    episode = n
                })
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(com.lagradost.cloudstream3.DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (id, ep) = data.split("|").let {
            if (it.size >= 2) it[0] to it[1] else it[0] to "1"
        }
        val links = Kyoto.loadLinksForEpisode(id, ep)
        links.forEach(callback)
        return links.isNotEmpty()
    }
}

/**
 * Pure-Kotlin port of the KYOTO Player request signer (no native lib needed).
 * Key de-obfuscation: byte[i] XOR ((i * 73 + 41) & 0xFF), same as libkyoto.so.
 * Msg: METHOD + "\n" + PATH + "\n" + sha256(body) + "\n" + ts + "\n" + nonce + "\n" + VER
 */
object Kyoto {
    private const val APP_ID = "com.kyotoplayer"
    private const val VER = "132"
    private const val OBF_HEX = "bcfb0102a27d4623de59016fcd8a511ff459d73f1671c2d2d7f1d2115e043308"

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

    fun signedHeaders(method: String, path: String, body: ByteArray = ByteArray(0)): Map<String, String> {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonceBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = Base64.encodeToString(nonceBytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val msg = "$method\n$path\n${sha256Hex(body)}\n$ts\n$nonce\n$VER"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(innerKey, "HmacSHA256"))
        val sig = mac.doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }
        return mapOf(
            "Accept" to "application/json",
            "os-version" to "34",
            "app-id" to APP_ID,
            "app-version" to VER,
            "device-model" to "Android",
            "X-Kyoto-Ts" to ts,
            "X-Kyoto-Nonce" to nonce,
            "X-Kyoto-Sig" to sig
        )
    }

    suspend fun loadLinksForEpisode(id: String, ep: String): List<ExtractorLink> {
        // episodes -> servers -> stream, then parse m3u8 qualities.
        // Endpoints still being locked against the live server; returns
        // empty until the signed handshake is confirmed.
        return emptyList()
    }
}
