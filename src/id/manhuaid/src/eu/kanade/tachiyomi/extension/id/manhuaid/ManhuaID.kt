package eu.kanade.tachiyomi.extension.id.manhuaid

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class ManhuaID : ParsedHttpSource() {

    override val name = "ManhuaID"

    override val baseUrl = "https://manhuaid.com"

    override val lang = "id"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    // popular
    override fun popularMangaSelector() = "a:has(img.card-img)"

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/index.php/project?page_project=$page", headers)

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.select("img").attr("alt")
        thumbnail_url = element.select("img").attr("abs:src")
    }

    override fun popularMangaNextPageSelector() = "[rel=nofollow]"

    // latest
    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/index.php/project?page_project=$page", headers)

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // search
    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url = "$baseUrl/search?".toHttpUrlOrNull()!!.newBuilder()
        url.addQueryParameter("q", query)
        filters.forEach { filter ->
            when (filter) {
                is ProjectFilter -> {
                    if (filter.toUriPart() == "project-filter-on") {
                        url = "$baseUrl/project?page_project=$page".toHttpUrlOrNull()!!.newBuilder()
                    }
                }
            }
        }
        return GET(url.toString(), headers)
    }
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = "li:last-child:not(.active) [rel=nofollow]"

    // manga details
    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        author = document.select("table").first().select("td")[3].text()
        title = document.select("h1").text()
        description = document.select(".text-justify").text()
        genre = document.select("span.badge.badge-success.mr-1.mb-1").joinToString { it.text() }
        status = document.select("td > span.badge.badge-success").text().let {
            parseStatus(it)
        }
        thumbnail_url = document.select("img.img-fluid").attr("abs:src")

        // add series type(manga/manhwa/manhua/other) thinggy to genre
        document.select("table tr:contains(Type) a, table a[href*=type]").firstOrNull()?.ownText()?.let {
            if (it.isEmpty().not() && genre!!.contains(it, true).not()) {
                genre += if (genre!!.isEmpty()) it else ", $it"
            }
        }
    }

    private fun parseStatus(status: String) = when {
        status.contains("Ongoing") -> SManga.ONGOING
        status.contains("Completed") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // chapters
    override fun chapterListSelector() = "table.table tr td:first-of-type a"

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = document.select(chapterListSelector()).map { chapterFromElement(it) }

        // Add timestamp to latest chapter, taken from "Updated On". so source which not provide chapter timestamp will have atleast one
        val date = document.select("table tr:contains(update) td").text()
        val checkChapter = document.select(chapterListSelector()).firstOrNull()
        if (date != "" && checkChapter != null) chapters[0].date_upload = parseDate(date)

        return chapters
    }

    private fun parseDate(date: String): Long {
        return SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH).parse(date)?.time ?: 0L
    }

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.text()
    }

    // pages
    override fun pageListParse(document: Document): List<Page> {
        return document.select("img.img-fluid.mb-0.mt-0").mapIndexed { i, element ->
            Page(i, "", element.attr("src"))
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not Used")

    override fun getFilterList() = FilterList(
        Filter.Header("NOTE: cant be used with search or other filter!"),
        Filter.Header("$name Project List page"),
        ProjectFilter(),
    )

    private class ProjectFilter : UriPartFilter(
        "Filter Project",
        arrayOf(
            Pair("Show all manga", ""),
            Pair("Show only project manga", "project-filter-on")
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
