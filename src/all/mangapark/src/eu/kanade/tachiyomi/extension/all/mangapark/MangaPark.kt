package eu.kanade.tachiyomi.extension.all.mangapark

import com.squareup.duktape.Duktape
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

open class MangaPark(
    override val lang: String,
    private val siteLang: String
) : ParsedHttpSource() {

    override val name: String = "MangaPark v3"

    override val baseUrl: String = "https://mangapark.net"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val mpFilters = MangaParkFilters()

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Site Browse Helper
    private fun browseMangaSelector(): String = "div#subject-list div.col"

    private fun browseNextPageSelector(): String =
        "div#mainer nav.d-none .pagination .page-item:last-of-type:not(.disabled)"

    private fun browseMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            setUrlWithoutDomain(element.select("a.fw-bold").attr("href"))
            title = element.select("a.fw-bold").text()
            thumbnail_url = element.select("a.position-relative img").attr("abs:src")
        }
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/browse?sort=update&page=$page")

    override fun latestUpdatesSelector(): String = browseMangaSelector()

    override fun latestUpdatesNextPageSelector(): String = browseNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SManga =
        browseMangaFromElement(element)

    // Popular
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/browse?sort=d007&page=$page")

    override fun popularMangaSelector(): String = browseMangaSelector()

    override fun popularMangaNextPageSelector(): String = browseNextPageSelector()

    override fun popularMangaFromElement(element: Element): SManga =
        browseMangaFromElement(element)


    // Search
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return when {
            query.startsWith(PREFIX_ID_SEARCH) -> fetchSearchIdManga(query)
            query.isNotBlank() -> fetchSearchManga(page, query)
            else -> fetchGenreSearchManga(page, filters)
        }
    }

    // Search With Manga ID
    private fun fetchSearchIdManga(idWithPrefix: String): Observable<MangasPage> {
        val id = idWithPrefix.removePrefix(PREFIX_ID_SEARCH)
        return client.newCall(GET("$baseUrl/comic/$id", headers))
            .asObservableSuccess()
            .map { response ->
                MangasPage(listOf(mangaDetailsParse(response.asJsoup())), false)
            }
    }

    // Search WIth Query
    private fun fetchSearchManga(page: Int, query: String): Observable<MangasPage> {
        return client.newCall(GET("$baseUrl/search?word=$query&page=$page", headers))
            .asObservableSuccess()
            .map { response ->
                searchMangaParse(response)
            }
    }

    // Search With Filter
    private fun fetchGenreSearchManga(page: Int, filters: FilterList): Observable<MangasPage> {
        val url = "$baseUrl/browse".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString()).let { mpFilters.addFiltersToUrl(it, filters) }

        return client.newCall(GET(url, headers))
            .asObservableSuccess()
            .map { response ->
                searchMangaParse(response)
            }
    }

    override fun searchMangaSelector(): String = "div#search-list div.col"

    override fun searchMangaNextPageSelector(): String =
        "div#mainer nav.d-none .pagination .page-item:last-of-type:not(.disabled)"

    override fun searchMangaFromElement(element: Element): SManga {
        return SManga.create().apply {
            setUrlWithoutDomain(element.select("a.fw-bold").attr("href"))
            title = element.select("a.fw-bold").text()
            thumbnail_url = element.select("a.position-relative img").attr("abs:src")
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val isBrowse = response.request.url.pathSegments[0] == "browse"
        val mangaSelector = if (isBrowse) browseMangaSelector() else searchMangaSelector()
        val nextPageSelector = if (isBrowse) browseNextPageSelector() else searchMangaNextPageSelector()

        val mangas = document.select(mangaSelector).map { element ->
            if (isBrowse) browseMangaFromElement(element) else searchMangaFromElement(element)
        }

        val hasNextPage = document.select(nextPageSelector).first() != null

        return MangasPage(mangas, hasNextPage)
    }

    // Manga Details
    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div#mainer div.container-fluid")

        return SManga.create().apply {
            setUrlWithoutDomain(infoElement.select("h3.item-title a").attr("href"))

            title = infoElement.select("h3.item-title").text()

            description = infoElement.select("div.limit-height-body")
                .select("h5.text-muted, div.limit-html")
                .joinToString("\n\n") { it.text().trim() } + "\n\nAlt. Titles" + infoElement
                .select("div.alias-set").text()
                .split("/").joinToString(", ") { it.trim() }

            author = infoElement.select("div.attr-item:contains(author) a")
                .joinToString { it.text().trim() }

            status = infoElement.select("div.attr-item:contains(status) span")
                .text().parseStatus()

            thumbnail_url = infoElement.select("div.detail-set div.attr-cover img").attr("abs:src")

            genre = infoElement.select("div.attr-item:contains(genres) span span")
                .joinToString { it.text().trim() }
        }
    }

    private fun String?.parseStatus() = if (this == null) {
        SManga.UNKNOWN
    } else when {
        this.toLowerCase(Locale.US).contains("ongoing") -> SManga.ONGOING
        this.toLowerCase(Locale.US).contains("hiatus") -> SManga.ONGOING
        this.toLowerCase(Locale.US).contains("completed") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListRequest(manga: SManga): Request {
        val sid = "$baseUrl${manga.url}".toHttpUrl().pathSegments[1].toInt()

        val jsonPayload = buildJsonObject {
            put("lang", siteLang)
            put("sid", sid)
        }

        val requestBody =
            jsonPayload.toString().toRequestBody("application/json;charset=UTF-8".toMediaType())

        val refererUrl = "$baseUrl/${manga.url}".toHttpUrl().newBuilder()
            .toString()
        val newHeaders = headersBuilder()
            .add("Content-Length", requestBody.contentLength().toString())
            .add("Content-Type", requestBody.contentType().toString())
            .set("Referer", refererUrl)
            .build()

        return POST(
            "$baseUrl/ajax.reader.subject.episodes.by.serial",
            headers = newHeaders,
            body = requestBody
        )
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val resToJson = json.parseToJsonElement(response.body!!.string()).jsonObject
        val document = Jsoup.parse(resToJson["html"]!!.jsonPrimitive.content)
        return document.select(chapterListSelector()).map { chapterFromElement(it) }
    }

    override fun chapterListSelector() = "div.episode-item"

    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select("a.chapt")

        return SChapter.create().apply {
            name = urlElement.text()
            date_upload = element.select("div.extra > i.ps-2").text().parseChapterDate()
            setUrlWithoutDomain(urlElement.attr("href").removeSuffix("/"))
        }
    }

    private fun String?.parseChapterDate(): Long {
        if (this == null) return 0L
        val value = this.split(' ')[0].toInt()

        return when (this.split(' ')[1].removeSuffix("s")) {
            "sec" -> Calendar.getInstance().apply {
                add(Calendar.SECOND, value * -1)
            }.timeInMillis
            "min" -> Calendar.getInstance().apply {
                add(Calendar.MINUTE, value * -1)
            }.timeInMillis
            "hour" -> Calendar.getInstance().apply {
                add(Calendar.HOUR_OF_DAY, value * -1)
            }.timeInMillis
            "day" -> Calendar.getInstance().apply {
                add(Calendar.DATE, value * -1)
            }.timeInMillis
            "week" -> Calendar.getInstance().apply {
                add(Calendar.DATE, value * 7 * -1)
            }.timeInMillis
            "month" -> Calendar.getInstance().apply {
                add(Calendar.MONTH, value * -1)
            }.timeInMillis
            "year" -> Calendar.getInstance().apply {
                add(Calendar.YEAR, value * -1)
            }.timeInMillis
            else -> {
                return 0L
            }
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        if (document.selectFirst("div.wrapper-deleted") != null) {
            throw Exception("The chapter content seems to be deleted.\n\nContact the site owner if possible.")
        }

        val script = document.select("script").html()
        val imgCdnHost = script.substringAfter("const imgCdnHost = \"").substringBefore("\";")
        val imgPathLisRaw = script.substringAfter("const imgPathLis = ").substringBefore(";")
        val imgPathLis = json.parseToJsonElement(imgPathLisRaw).jsonArray
        val amPass = script.substringAfter("const amPass = ").substringBefore(";")
        val amWord = script.substringAfter("const amWord = ").substringBefore(";")

        val decryptScript =
            cryptoJS + "CryptoJS.AES.decrypt($amWord, $amPass).toString(CryptoJS.enc.Utf8);"

        val imgWordLisRaw = Duktape.create().use { it.evaluate(decryptScript).toString() }
        val imgWordLis = json.parseToJsonElement(imgWordLisRaw).jsonArray

        return imgWordLis.mapIndexed { i, imgWordE ->
            val imgPath = imgPathLis[i].jsonPrimitive.content
            val imgWord = imgWordE.jsonPrimitive.content

            Page(i, "", "$imgCdnHost$imgPath?$imgWord")
        }
    }

    private val cryptoJS by lazy {
        client.newCall(GET(CryptoJSUrl, headers)).execute().body!!.string()
    }


    override fun getFilterList() = mpFilters.getFilterList()

    //Unused Stuff

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        throw UnsupportedOperationException("Not used")

    override fun imageUrlParse(document: Document): String =
        throw UnsupportedOperationException("Not used")




    companion object {

        const val PREFIX_ID_SEARCH = "id:"

        const val CryptoJSUrl =
            "https://cdnjs.cloudflare.com/ajax/libs/crypto-js/4.0.0/crypto-js.min.js"
    }
}
