package eu.kanade.tachiyomi.extension.en.manhuamanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ManhuaManga : ParsedHttpSource() {
    override val name = "ManhuaManga.net"
    override val baseUrl = "https://manhuamanga.net"
    override val lang = "en"
    override val supportsLatest = true

    override fun popularMangaSelector() = ".home-truyendecu"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()
    override fun chapterListSelector() = "#list-chapter > div.row > div > ul > li:nth-child(n)"

    override fun popularMangaNextPageSelector() = "li.active+li a[data-page]"
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/most-views/page/$page", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/latest-updates/page/$page", headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/?s=$query", headers)
        } else {
            val url = "$baseUrl/category/".toHttpUrlOrNull()!!.newBuilder()
            filters.forEach { filter ->
                when (filter) {

                    is GenreFilter -> url.addPathSegment(filter.toUriPart())
                }
            }
            url.addPathSegment("page")
            url.addPathSegment("$page")
            GET(url.toString(), headers)
        }
    }
    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.setUrlWithoutDomain(element.select("a").attr("href"))
        manga.title = element.select("a").attr("title")
        manga.thumbnail_url = element.select("a img").attr("src")

        return manga
    }
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    protected fun getXhrChapters(mangaId: String): Document {
        val xhrHeaders = headersBuilder().add("Content-Type: application/x-www-form-urlencoded; charset=UTF-8")
            .build()
        val body = "action=tw_ajax&type=list_chap&id=$mangaId".toRequestBody(null)
        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", xhrHeaders, body)).execute().asJsoup()
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val dataIdSelector = "input[id^=id_post]"

        return getXhrChapters(document.select(dataIdSelector).attr("value")).select("option").map { chapterFromElement(it) }.reversed()
    }

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        element.let { urlElement ->
            chapter.setUrlWithoutDomain(urlElement.attr("value"))
            chapter.name = urlElement.text()
        }
        chapter.date_upload = 0

        return chapter
    }

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.select("h3.title").text()
        description = document.select("div.desc-text > p").text()
        thumbnail_url = document.select("div.books > div > img").attr("src")
        author = document.select("div.info > div:nth-child(1) > a").attr("title")
        genre = document.select("div.info > div:nth-child(2) > a").joinToString { it.text() }
        status = document.select("div.info > div:nth-child(3) > span").text().let {
            when {
                it.contains("Ongoing") -> SManga.ONGOING
                it.contains("Completed") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun pageListParse(document: Document): List<Page> = mutableListOf<Page>().apply {
        document.select("p img").forEachIndexed { index, element ->
            add(Page(index, "", element.attr("src")))
        }
    }

    override fun imageUrlRequest(page: Page) = throw Exception("Not used")
    override fun imageUrlParse(document: Document) = throw Exception("Not used")

    override fun getFilterList() = FilterList(
        Filter.Header("NOTE: Ignored if using text search!"),
        Filter.Separator(),
        GenreFilter(getGenreList())
    )
    class GenreFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Category", vals)

    private fun getGenreList() = arrayOf(
        Pair("All", ""),
        Pair("Action", "action"),
        Pair("Adventure", "adventure"),
        Pair("Comedy", "comedy"),
        Pair("Comic", "comic"),
        Pair("Drama", "drama"),
        Pair("Ecchi", "ecchi"),
        Pair("Fantasy", "fantasy"),
        Pair("Harem", "harem"),
        Pair("Historical", "historical"),
        Pair("Isekai", "isekai"),
        Pair("Josei", "josei"),
        Pair("Manhua", "manhua"),
        Pair("Manhwa", "manhwa"),
        Pair("Martial arts", "martial-arts"),
        Pair("Moder", "moder"),
        Pair("Mystery", "mystery"),
        Pair("Psychological", "psychological"),
        Pair("Romance", "romance"),
        Pair("School Life", "school-life"),
        Pair("Sci Fi", "sci-fi"),
        Pair("Seinen", "seinen"),
        Pair("Shoujo", "shoujo"),
        Pair("Shounen", "shounen"),
        Pair("Shounen ai", "shounen-ai"),
        Pair("Slice of Life", "slice-of-life"),
        Pair("Super power", "super-power"),
        Pair("Tragedy", "tragedy"),
        Pair("Webtoon", "webtoon"),
        Pair("Webtoons", "webtoons"),
    )
    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
