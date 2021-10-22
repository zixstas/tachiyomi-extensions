package eu.kanade.tachiyomi.extension.ru.comx

import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class ComX : ParsedHttpSource() {
    override val name = "Com-x"

    override val baseUrl = "https://com-x.life"

    override val lang = "ru"

    override val supportsLatest = true

    private val userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:72.0) Gecko/20100101 Firefox/72.0"

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor(RateLimitInterceptor(3))
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", userAgent)
        .add("Referer", baseUrl)

    override fun popularMangaSelector() = "div.shortstory1"

    override fun latestUpdatesSelector() = "ul.last-comix li"

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/comix-read/page/$page/", headers)

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }

        if (mangas.isEmpty()) throw UnsupportedOperationException("Error: Open in WebView and solve the Antirobot!")

        val hasNextPage = popularMangaNextPageSelector().let { selector ->
            document.select(selector).first()
        } != null

        return MangasPage(mangas, hasNextPage)
    }

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.thumbnail_url = baseUrl + element.select("img").first().attr("src")
        element.select("div.info-poster1 a").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.text()
        }
        return manga
    }

    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.thumbnail_url = baseUrl + element.select("img").first().attr("src")
        element.select("a.comix-last-title").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.text()
        }
        return manga
    }

    override fun popularMangaNextPageSelector() = ".pnext:last-child"

    override fun latestUpdatesNextPageSelector(): Nothing? = null

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/index.php?do=xsearch&searchCat=comix-read&page=$page".toHttpUrlOrNull()!!.newBuilder()
        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is TypeList -> filter.state.forEach { type ->
                    if (type.state) {
                        url.addQueryParameter("field[type][${type.id}]", 1.toString())
                    }
                }
                is PubList -> filter.state.forEach { publisher ->
                    if (publisher.state) {
                        url.addQueryParameter("field[publisher][${publisher.id}]", 1.toString())
                    }
                }
                is GenreList -> filter.state.forEach { genre ->
                    if (genre.state) {
                        url.addQueryParameter("field[genre][${genre.id}]", 1.toString())
                    }
                }
            }
        }
        if (query.isNotEmpty()) {
            return POST(
                "$baseUrl/comix-read/",
                body = FormBody.Builder()
                    .add("do", "search")
                    .add("story", query)
                    .add("subaction", "search")
                    .build(),
                headers = headers
            )
        }
        return GET(url.toString(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div.maincont").first()

        val manga = SManga.create()
        manga.author = infoElement.select(".fullstory__infoSection:eq(1) > .fullstory__infoSectionContent").text()
        manga.genre = infoElement.select(".fullstory__infoSection:eq(2) > .fullstory__infoSectionContent").text()
            .split(",").plusElement("Комикс").joinToString { it.trim() }

        manga.status = parseStatus(infoElement.select(".fullstory__infoSection:eq(3) > .fullstory__infoSectionContent").text())

        val text = infoElement.select("*").text()
        if (!text.contains("Добавить описание на комикс")) {
            val fromRemove = "Отслеживать"
            val toRemove = "Читать комикс"
            val desc = text.removeRange(0, text.indexOf(fromRemove) + fromRemove.length)
            manga.description = desc.removeRange(desc.indexOf(toRemove) + toRemove.length, desc.length)
        }

        val src = infoElement.select("img").attr("src")
        if (src.contains(baseUrl)) {
            manga.thumbnail_url = src
        } else {
            manga.thumbnail_url = baseUrl + src
        }
        return manga
    }

    private fun parseStatus(element: String): Int = when {
        element.contains("Продолжается") ||
            element.contains(" из ") -> SManga.ONGOING
        element.contains("Заверш") ||
            element.contains("Лимитка") ||
            element.contains("Ван шот") ||
            element.contains("Графический роман") -> SManga.COMPLETED

        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "li[id^=cmx-]"

    private fun chapterResponseParse(document: Document): List<SChapter> {
        return document.select(chapterListSelector()).map { chapterFromElement(it) }
    }

    private fun chapterPageListParse(document: Document): List<String> {
        return document.select("span[class=\"\"]").map { it.text() }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val id = response.request.url.toString().removePrefix("$baseUrl/").split('-')[0]

        val list = mutableListOf<SChapter>()
        list += chapterResponseParse(document)

        val pages = chapterPageListParse(document).distinct()

        for (page in pages) {
            val post = POST(
                "$baseUrl/engine/mods/comix/listPages.php",
                body = FormBody.Builder()
                    .add("newsid", id)
                    .add("page", page)
                    .build(),
                headers = headers
            )

            list += chapterResponseParse(client.newCall(post).execute().asJsoup())
        }

        return list
    }
    private val simpleDateFormat by lazy { SimpleDateFormat("dd.MM.yyyy", Locale.US) }
    private fun parseDate(date: String?): Long {
        date ?: return 0L
        return try {
            simpleDateFormat.parse(date)!!.time
        } catch (_: Exception) {
            Date().time
        }
    }
    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select("a").first()
        val urlText = urlElement.text()
        val chapter = SChapter.create()
        chapter.name = urlText.split('/')[0] // Remove english part of name
        chapter.setUrlWithoutDomain(urlElement.attr("href"))
        chapter.date_upload = parseDate(element.select("span:eq(2)").text())
        return chapter
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body!!.string()
        val baseImgUrl = "https://img.com-x.life/comix/"

        val beginTag = "\"images\":["
        val beginIndex = html.indexOf(beginTag)
        val endIndex = html.indexOf("]", beginIndex)

        val urls: List<String> = html.substring(beginIndex + beginTag.length, endIndex)
            .split(',').map {
                val img = it.replace("\\", "").replace("\"", "")
                baseImgUrl + img
            }

        val pages = mutableListOf<Page>()
        for (i in urls.indices) {
            pages.add(Page(i, "", urls[i]))
        }

        return pages
    }

    override fun pageListParse(document: Document): List<Page> {
        throw Exception("Not used")
    }

    override fun imageUrlParse(document: Document) = ""

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!, headers)
    }

    private class CheckFilter(name: String, val id: String) : Filter.CheckBox(name)

    private class TypeList(types: List<CheckFilter>) : Filter.Group<CheckFilter>("Тип выпуска", types)
    private class PubList(publishers: List<CheckFilter>) : Filter.Group<CheckFilter>("Издатели", publishers)
    private class GenreList(genres: List<CheckFilter>) : Filter.Group<CheckFilter>("Жанры", genres)

    override fun getFilterList() = FilterList(
        TypeList(getTypeList()),
        PubList(getPubList()),
        GenreList(getGenreList()),
    )
    private fun getTypeList() = listOf(
        CheckFilter("Лимитка", "1"),
        CheckFilter("Ван шот", "2"),
        CheckFilter("Графический Роман", "3"),
        CheckFilter("Онгоинг", "4"),
    )
    private fun getPubList() = listOf(
        CheckFilter("Amalgam Comics", "1"),
        CheckFilter("Avatar Press", "2"),
        CheckFilter("Bongo", "3"),
        CheckFilter("Boom! Studios", "4"),
        CheckFilter("DC Comics", "5"),
        CheckFilter("DC/WildStorm", "6"),
        CheckFilter("Dark Horse Comics", "7"),
        CheckFilter("Disney", "8"),
        CheckFilter("Dynamite Entertainment", "9"),
        CheckFilter("IDW Publishing", "10"),
        CheckFilter("Icon Comics", "11"),
        CheckFilter("Image Comics", "12"),
        CheckFilter("Marvel Comics", "13"),
        CheckFilter("Marvel Knights", "14"),
        CheckFilter("Max", "15"),
        CheckFilter("Mirage", "16"),
        CheckFilter("Oni Press", "17"),
        CheckFilter("ShadowLine", "18"),
        CheckFilter("Titan Comics", "19"),
        CheckFilter("Top Cow", "20"),
        CheckFilter("Ubisoft Entertainment", "21"),
        CheckFilter("Valiant Comics", "22"),
        CheckFilter("Vertigo", "23"),
        CheckFilter("Viper Comics", "24"),
        CheckFilter("Vortex", "25"),
        CheckFilter("WildStorm", "26"),
        CheckFilter("Zenescope Entertainment", "27"),
    )
    private fun getGenreList() = listOf(
        CheckFilter("Антиутопия", "1"),
        CheckFilter("Бандитский ситком", "2"),
        CheckFilter("Боевик", "3"),
        CheckFilter("Вестерн", "4"),
        CheckFilter("Детектив", "5"),
        CheckFilter("Драма", "6"),
        CheckFilter("История", "7"),
        CheckFilter("Киберпанк", "8"),
        CheckFilter("Комедия", "9"),
        CheckFilter("Космоопера", "10"),
        CheckFilter("Криминал", "11"),
        CheckFilter("МелоДрама", "12"),
        CheckFilter("Мистика", "13"),
        CheckFilter("Нуар", "14"),
        CheckFilter("Постапокалиптика", "15"),
        CheckFilter("Приключения", "16"),
        CheckFilter("Сверхъестественное", "17"),
        CheckFilter("Сказка", "18"),
        CheckFilter("Спорт", "19"),
        CheckFilter("Стимпанк", "20"),
        CheckFilter("Триллер", "21"),
        CheckFilter("Ужасы", "22"),
        CheckFilter("Фантастика", "23"),
        CheckFilter("Фэнтези", "24"),
        CheckFilter("Черный юмор", "25"),
        CheckFilter("Экшн", "26"),
        CheckFilter("Боевые искусства", "27"),
        CheckFilter("Научная Фантастика", "28"),
        CheckFilter("Психоделика", "29"),
        CheckFilter("Психология", "30"),
        CheckFilter("Романтика", "31"),
        CheckFilter("Трагедия", "32"),
    )
}
