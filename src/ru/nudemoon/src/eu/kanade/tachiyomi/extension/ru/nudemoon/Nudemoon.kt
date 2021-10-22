package eu.kanade.tachiyomi.extension.ru.nudemoon

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class Nudemoon : ParsedHttpSource() {

    override val name = "Nude-Moon"

    override val baseUrl = "https://nude-moon.net"

    override val lang = "ru"

    override val supportsLatest = true

    private val cookiesHeader by lazy {
        val cookies = mutableMapOf<String, String>()
        cookies["NMfYa"] = "1"
        buildCookies(cookies)
    }

    private fun buildCookies(cookies: Map<String, String>) =
        cookies.entries.joinToString(separator = "; ", postfix = ";") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

    override val client = network.client.newBuilder()
        .addNetworkInterceptor { chain ->
            val newReq = chain
                .request()
                .newBuilder()
                .addHeader("Cookie", cookiesHeader)
                .addHeader("Referer", baseUrl)
                .build()

            chain.proceed(newReq)
        }.build()!!

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/all_manga?views&rowstart=${30 * (page - 1)}", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/all_manga?date&rowstart=${30 * (page - 1)}", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Search by query on this site works really badly, i don't even sure of the need to implement it
        val url = if (query.isNotEmpty()) {
            "$baseUrl/search?stext=${URLEncoder.encode(query, "CP1251")}&rowstart=${30 * (page - 1)}"
        } else {
            var genres = ""
            var order = ""
            for (filter in if (filters.isEmpty()) getFilterList() else filters) {
                when (filter) {
                    is GenreList -> {
                        filter.state.forEach { f ->
                            if (f.state) {
                                genres += f.id + '+'
                            }
                        }
                    }
                }
            }

            if (genres.isNotEmpty()) {
                for (filter in filters) {
                    when (filter) {
                        is OrderBy -> {
                            // The site has no ascending order
                            order = arrayOf("&date", "&views", "&like")[filter.state!!.index]
                        }
                    }
                }
                "$baseUrl/tags/${genres.dropLast(1)}$order&rowstart=${30 * (page - 1)}"
            } else {
                for (filter in filters) {
                    when (filter) {
                        is OrderBy -> {
                            // The site has no ascending order
                            order = arrayOf("all_manga?date", "all_manga?views", "all_manga?like")[filter.state!!.index]
                        }
                    }
                }
                "$baseUrl/$order&rowstart=${30 * (page - 1)}"
            }
        }
        return GET(url, headers)
    }

    override fun popularMangaSelector() = "tr[valign=top]"

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        manga.thumbnail_url = element.select("img[class^=news]").attr("abs:src")
        element.select("a:has(h2)").let {
            manga.title = it.text()
            manga.setUrlWithoutDomain(it.attr("href"))
        }

        return manga
    }

    override fun latestUpdatesFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun searchMangaFromElement(element: Element): SManga =
        popularMangaFromElement(element)

    override fun popularMangaNextPageSelector() = "a.small:contains(Следующая)"

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        manga.author = document.select("div.tbl1 a[href^=mangaka]").text()
        manga.genre = document.select("div.tbl2 span.tag-links a").joinToString { it.text() }
        manga.description = document.select(".description").text()
        manga.thumbnail_url = document.select("tr[valign=top] img[class^=news]").attr("abs:src")

        return manga
    }

    override fun chapterListRequest(manga: SManga): Request {

        val chapterUrl = if (manga.title.contains("\\s#\\d+".toRegex()))
            "/vse_glavy/" + manga.title.split("\\s#\\d+".toRegex())[0].replace("\\W".toRegex(), "_")
        else
            manga.url

        return GET(baseUrl + chapterUrl, headers)
    }

    override fun chapterListSelector() = popularMangaSelector()

    override fun chapterListParse(response: Response): List<SChapter> {
        val responseUrl = response.request.url.toString()
        val document = response.asJsoup()

        if (!responseUrl.contains("/vse_glavy/")) {
            return listOf(chapterFromElement(document))
        }

        // Order chapters by its number 'cause on the site they are in random order
        return document.select(chapterListSelector()).sortedByDescending {
            val regex = "#(\\d+)".toRegex()
            val chapterName = it.select("img[class^=news]").first().parent().attr("title")
            regex.find(chapterName)?.groupValues?.get(1)?.toInt() ?: 0
        }.map { chapterFromElement(it) }
    }

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()

        val infoElem = element.select("tr[valign=top]").first().parent()
        val chapterName = infoElem.select("h1, h2").text()
        var chapterUrl = infoElem.select("a[title]:has(img)").attr("href")
        if (!chapterUrl.contains("-online")) {
            chapterUrl = chapterUrl.replace("/\\d+".toRegex(), "$0-online")
        } else {
            chapter.chapter_number = 1F
        }

        chapter.setUrlWithoutDomain(if (!chapterUrl.startsWith("/")) "/$chapterUrl" else chapterUrl)
        chapter.name = chapterName
        chapter.date_upload = infoElem.text().substringAfter("Дата:").substringBefore("Просмотров").trim().let {
            try {
                SimpleDateFormat("dd MMMM yyyy", Locale("ru")).parse(it)?.time ?: 0L
            } catch (e: Exception) {
                0
            }
        }

        return chapter
    }

    override fun pageListParse(response: Response): List<Page> {
        val imgScript = response.asJsoup().select("script:containsData(var images)").first().data()

        return Regex("""images\[(\d+)].src\s=\s'(http.*)'""").findAll(imgScript).map {
            Page(it.groupValues[1].toInt(), imageUrl = it.groupValues[2])
        }.toList()
    }

    override fun imageUrlParse(document: Document) = throw Exception("Not Used")

    override fun pageListParse(document: Document): List<Page> = throw Exception("Not Used")

    private class Genre(name: String, val id: String = name.replace(' ', '_')) : Filter.CheckBox(name.capitalize())
    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Тэги", genres)
    private class OrderBy : Filter.Sort(
        "Сортировка",
        arrayOf("Дата", "Просмотры", "Лайки"),
        Selection(1, false)
    )

    override fun getFilterList() = FilterList(
        OrderBy(),
        GenreList(getGenreList())
    )

    private fun getGenreList() = listOf(
        Genre("анал"),
        Genre("без цензуры"),
        Genre("беременные"),
        Genre("близняшки"),
        Genre("большие груди"),
        Genre("в бассейне"),
        Genre("в больнице"),
        Genre("в ванной"),
        Genre("в общественном месте"),
        Genre("в первый раз"),
        Genre("в транспорте"),
        Genre("в туалете"),
        Genre("гарем"),
        Genre("гипноз"),
        Genre("горничные"),
        Genre("горячий источник"),
        Genre("групповой секс"),
        Genre("драма"),
        Genre("запредельное"),
        Genre("золотой дождь"),
        Genre("зрелые женщины"),
        Genre("идолы"),
        Genre("извращение"),
        Genre("измена"),
        Genre("имеют парня"),
        Genre("клизма"),
        Genre("колготки"),
        Genre("комиксы"),
        Genre("комиксы 3D"),
        Genre("косплей"),
        Genre("мастурбация"),
        Genre("мерзкий мужик"),
        Genre("много спермы"),
        Genre("молоко"),
        Genre("монстры"),
        Genre("на камеру"),
        Genre("на природе"),
        Genre("обычный секс"),
        Genre("огромный член"),
        Genre("пляж"),
        Genre("подглядывание"),
        Genre("принуждение"),
        Genre("продажность"),
        Genre("пьяные"),
        Genre("рабыни"),
        Genre("романтика"),
        Genre("с ушками"),
        Genre("секс игрушки"),
        Genre("спящие"),
        Genre("страпон"),
        Genre("студенты"),
        Genre("суккуб"),
        Genre("тентакли"),
        Genre("толстушки"),
        Genre("трапы"),
        Genre("ужасы"),
        Genre("униформа"),
        Genre("учитель и ученик"),
        Genre("фемдом"),
        Genre("фетиш"),
        Genre("фурри"),
        Genre("футанари"),
        Genre("футфетиш"),
        Genre("фэнтези"),
        Genre("цветная"),
        Genre("чикан"),
        Genre("чулки"),
        Genre("шимейл"),
        Genre("эксгибиционизм"),
        Genre("юмор"),
        Genre("юри"),
        Genre("ahegao"),
        Genre("BDSM"),
        Genre("ganguro"),
        Genre("gender bender"),
        Genre("megane"),
        Genre("mind break"),
        Genre("monstergirl"),
        Genre("netorare"),
        Genre("nipple penetration"),
        Genre("titsfuck"),
        Genre("x-ray")
    )
}
