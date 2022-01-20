package eu.kanade.tachiyomi.extension.ru.selfmanga

import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

class Selfmanga : ParsedHttpSource() {

    override val name = "Selfmanga"

    override val baseUrl = "https://selfmanga.live"

    override val lang = "ru"

    override val supportsLatest = true

    private val rateLimitInterceptor = RateLimitInterceptor(2)

    override val client: OkHttpClient = network.client.newBuilder()
        .addNetworkInterceptor(rateLimitInterceptor).build()

    override fun popularMangaSelector() = "div.tile"

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/list?sortType=rate&offset=${70 * (page - 1)}", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/list?sortType=updated&offset=${70 * (page - 1)}", headers)

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.thumbnail_url = element.select("img.lazy").first().attr("data-original")
        element.select("h3 > a").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.attr("title")
        }
        return manga
    }

    override fun latestUpdatesFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun popularMangaNextPageSelector() = "a.nextLink"

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search/advanced".toHttpUrlOrNull()!!.newBuilder()
        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is GenreList -> filter.state.forEach { genre ->
                    if (genre.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(genre.id, arrayOf("=", "=in", "=ex")[genre.state])
                    }
                }
                is Category -> filter.state.forEach { category ->
                    if (category.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(category.id, arrayOf("=", "=in", "=ex")[category.state])
                    }
                }
            }
        }
        if (query.isNotEmpty()) {
            url.addQueryParameter("q", query)
        }
        return GET(url.toString().replace("=%3D", "="), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    // max 200 results
    override fun searchMangaNextPageSelector(): Nothing? = null

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select(".expandable").first()

        val manga = SManga.create()
        manga.title = document.select("h1.names .name").text()
        manga.author = infoElement.select("span.elem_author").first()?.text()
        manga.genre = infoElement.select("span.elem_genre").text().replace(" ,", ",")
        manga.description = document.select("div#tab-description  .manga-description").text()
        manga.status = parseStatus(infoElement.html())
        manga.thumbnail_url = infoElement.select("img").attr("data-full")
        return manga
    }

    private fun parseStatus(element: String): Int = when {
        element.contains("Запрещена публикация произведения по копирайту") || element.contains("ЗАПРЕЩЕНА К ПУБЛИКАЦИИ НА ТЕРРИТОРИИ РФ!") -> SManga.LICENSED
        element.contains("<b>Перевод:</b> продолжается") -> SManga.ONGOING
        element.contains("<b>Сингл</b>") || element.contains(", завер") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "div.chapters-link tbody tr"

    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select("a").first()
        val urlText = urlElement.text()

        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.attr("href") + "?mtr=1")
        if (urlText.endsWith(" новое")) {
            chapter.name = urlText.dropLast(6)
        } else {
            chapter.name = urlText
        }
        chapter.date_upload = element.select("td.hidden-xxs").last()?.text()?.let {
            try {
                SimpleDateFormat("dd/MM/yy", Locale.US).parse(it)?.time ?: 0L
            } catch (e: ParseException) {
                SimpleDateFormat("dd.MM.yy", Locale.US).parse(it)?.time ?: 0L
            }
        } ?: 0
        return chapter
    }

    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        val basic = Regex("""\s*([0-9]+)(\s-\s)([0-9]+)\s*""")
        val extra = Regex("""\s*([0-9]+\sЭкстра)\s*""")
        val single = Regex("""\s*Сингл\s*""")
        when {
            basic.containsMatchIn(chapter.name) -> {
                basic.find(chapter.name)?.let {
                    val number = it.groups[3]?.value!!
                    chapter.chapter_number = number.toFloat()
                }
            }
            extra.containsMatchIn(chapter.name) -> // Extra chapters doesn't contain chapter number
                chapter.chapter_number = -2f
            single.containsMatchIn(chapter.name) -> // Oneshoots, doujinshi and other mangas with one chapter
                chapter.chapter_number = 1f
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body!!.string()
        val beginIndex = html.indexOf("rm_h.initReader( [")
        val endIndex = html.indexOf(");", beginIndex)
        val trimmedHtml = html.substring(beginIndex, endIndex)

        val p = Pattern.compile("'.*?','.*?',\".*?\"")
        val m = p.matcher(trimmedHtml)

        val pages = mutableListOf<Page>()

        var i = 0
        while (m.find()) {
            val urlParts = m.group().replace("[\"\']+".toRegex(), "").split(',')
            val url = if (urlParts[1].isEmpty() && urlParts[2].startsWith("/static/")) {
                baseUrl + urlParts[2]
            } else {
                if (urlParts[1].endsWith("/manga/")) {
                    urlParts[0] + urlParts[2]
                } else {
                    urlParts[1] + urlParts[0] + urlParts[2]
                }
            }
            pages.add(Page(i++, "", url))
        }
        return pages
    }

    override fun pageListParse(document: Document): List<Page> {
        throw Exception("Not used")
    }

    override fun imageUrlParse(document: Document) = ""

    override fun imageRequest(page: Page): Request {
        val imgHeader = Headers.Builder().apply {
            add("User-Agent", "Mozilla/5.0 (Windows NT 6.3; WOW64)")
            add("Referer", baseUrl)
        }.build()
        return GET(page.imageUrl!!, imgHeader)
    }

    private class Genre(name: String, val id: String) : Filter.TriState(name)
    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Genres", genres)
    private class Category(categories: List<Genre>) : Filter.Group<Genre>("Category", categories)

    /* [...document.querySelectorAll("tr.advanced_option:nth-child(1) > td:nth-child(3) span.js-link")]
    *  .map(el => `Genre("${el.textContent.trim()}", $"{el.getAttribute('onclick')
    *  .substr(31,el.getAttribute('onclick').length-33)"})`).join(',\n')
    *  on https://selfmanga.ru/search/advanced
    */
    override fun getFilterList() = FilterList(
        Category(getCategoryList()),
        GenreList(getGenreList())
    )

    private fun getCategoryList() = listOf(
        Genre("Артбук", "el_5894"),
        Genre("Веб", "el_2160"),
        Genre("Журнал", "el_4983"),
        Genre("Ранобэ", "el_5215"),
        Genre("Сборник", "el_2157")
    )

    private fun getGenreList() = listOf(
        Genre("боевик", "el_2155"),
        Genre("боевые искусства", "el_2143"),
        Genre("вампиры", "el_2148"),
        Genre("гарем", "el_2142"),
        Genre("гендерная интрига", "el_2156"),
        Genre("героическое фэнтези", "el_2146"),
        Genre("детектив", "el_2152"),
        Genre("дзёсэй", "el_2158"),
        Genre("додзинси", "el_2141"),
        Genre("драма", "el_2118"),
        Genre("ёнкома", "el_2161"),
        Genre("история", "el_2119"),
        Genre("комедия", "el_2136"),
        Genre("махо-сёдзё", "el_2147"),
        Genre("мистика", "el_2132"),
        Genre("научная фантастика", "el_2133"),
        Genre("повседневность", "el_2135"),
        Genre("постапокалиптика", "el_2151"),
        Genre("приключения", "el_2130"),
        Genre("психология", "el_2144"),
        Genre("романтика", "el_2121"),
        Genre("сверхъестественное", "el_2159"),
        Genre("сёдзё", "el_2122"),
        Genre("сёдзё-ай", "el_2128"),
        Genre("сёнэн", "el_2134"),
        Genre("сёнэн-ай", "el_2139"),
        Genre("спорт", "el_2129"),
        Genre("сэйнэн", "el_5838"),
        Genre("трагедия", "el_2153"),
        Genre("триллер", "el_2150"),
        Genre("ужасы", "el_2125"),
        Genre("фантастика", "el_2140"),
        Genre("фэнтези", "el_2131"),
        Genre("школа", "el_2127"),
        Genre("этти", "el_4982")
    )
}
