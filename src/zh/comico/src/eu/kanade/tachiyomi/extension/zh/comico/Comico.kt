package eu.kanade.tachiyomi.extension.zh.comico

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

abstract class Comico(
    override val name: String,
    open val urlModifier: String,
    override val supportsLatest: Boolean
) : ParsedHttpSource() {

    override val baseUrl = "https://www.comico.com.tw"

    override val lang = "zh"

    override val client: OkHttpClient = network.cloudflareClient

    val json: Json by injectLazy()

    // Popular

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl$urlModifier/official/finish/?order=ALLSALES", headers)
    }

    override fun popularMangaSelector() = "ul.list-article02__list li.list-article02__item a"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        manga.url = element.attr("href").substringAfter(baseUrl + urlModifier)
        manga.title = element.attr("title")
        manga.thumbnail_url = element.select("img").first().attr("abs:src")

        return manga
    }

    override fun popularMangaNextPageSelector() = "No next page"

    // Latest

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesSelector() = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesFromElement(element: Element): SManga = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException("Not used")

    // Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/search/index.nhn?searchWord=$query", headers)
    }

    override fun searchMangaSelector() = "div#officialList ul.list-article02__list li.list-article02__item a"

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // Manga details

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + urlModifier + manga.url, headers)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div.article-hero05")

        val manga = SManga.create()
        manga.title = infoElement.select("h1").text()
        manga.author = infoElement.select("p.article-hero05__author").text()
        manga.description = infoElement.select("p.article-hero05__sub-description").text()
        manga.genre = infoElement.select("div.article-hero05__meta a").text()
        manga.status = parseStatus(infoElement.select("div.article-hero05__meta p:not(:has(a))").first().text())
        manga.thumbnail_url = infoElement.select("img").attr("src")

        return manga
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SManga.UNKNOWN
        status.contains("每") -> SManga.ONGOING
        status.contains("完結作品") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // Chapters

    override fun chapterListSelector() = throw UnsupportedOperationException("Not used")

    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException("Not used")

    override fun chapterListRequest(manga: SManga): Request {
        val chapterListHeaders = headersBuilder()
            .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .build()

        val body = FormBody.Builder()
            .add("titleNo", manga.url.replace("/", ""))
            .build()

        return POST("$baseUrl/api/getArticleList.nhn", chapterListHeaders, body)
    }

    fun chapterFromJson(jsonObject: JsonObject): SChapter {
        val chapter = SChapter.create()

        chapter.name = jsonObject["subtitle"]!!.jsonPrimitive.content
        chapter.setUrlWithoutDomain(jsonObject["articleDetailUrl"]!!.jsonPrimitive.content)
        chapter.date_upload = parseDate(jsonObject["date"]!!.jsonPrimitive.content)

        return chapter
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = mutableListOf<SChapter>()

        json.decodeFromString<JsonObject>(response.body!!.string())["result"]!!
            .jsonObject["list"]!!
            .jsonArray
            .forEach {
                if (it.jsonObject["freeFlg"]!!.jsonPrimitive.content == "Y") {
                    chapters.add(chapterFromJson(it.jsonObject))
                }
            }

        return chapters.reversed()
    }

    private fun parseDate(date: String): Long {
        return SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()).parse(date)?.time ?: 0L
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()

        // First image is in the body
        document.select("div.comic-image img")
            .map { pages.add(Page(pages.size, "", it.attr("abs:src"))) }
        // If there are more images, they're in a script
        document.select("script:containsData(imageData)").first().data().let {
            if (it.isNotEmpty()) {
                it.substringAfter("imageData:[").substringBefore("]").trim().split(",")
                    .forEach { img -> pages.add(Page(pages.size, "", img.replace("\'", ""))) }
            }
        }

        return pages
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    override fun getFilterList() = FilterList()
}
