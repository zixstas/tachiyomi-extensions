package eu.kanade.tachiyomi.extension.id.komikcast

import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.multisrc.wpmangastream.WPMangaStream
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class KomikCast : WPMangaStream("Komik Cast", "https://komikcast.com", "id") {
    // Formerly "Komik Cast (WP Manga Stream)"
    override val id = 972717448578983812

    private val rateLimitInterceptor = RateLimitInterceptor(3)

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor(rateLimitInterceptor)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9")
        .add("Accept-language", "en-US,en;q=0.9,id;q=0.8")
        .add("Referer", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:25.0) Gecko/20100101 Firefox/25.0")

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", baseUrl)
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }
    override fun popularMangaSelector() = "div.list-update_item"

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/daftar-komik/page/$page/?orderby=popular", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/komik/page/$page/", headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotBlank()) {
            val url = "$baseUrl/page/$page".toHttpUrlOrNull()!!.newBuilder()
            val pattern = "\\s+".toRegex()
            val q = query.replace(pattern, "+")
            if (query.isNotEmpty()) {
                url.addQueryParameter("s", q)
            } else {
                url.addQueryParameter("s", "")
            }
            url.toString()
        } else {
            var url = "$baseUrl/daftar-komik/page/$page".toHttpUrlOrNull()!!.newBuilder()
            var orderBy: String
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is StatusFilter -> url.addQueryParameter("status", arrayOf("", "ongoing", "completed")[filter.state])
                    is GenreListFilter -> {
                        val genreInclude = mutableListOf<String>()
                        filter.state.forEach {
                            if (it.state == 1) {
                                genreInclude.add(it.id)
                            }
                        }
                        if (genreInclude.isNotEmpty()) {
                            genreInclude.forEach { genre ->
                                url.addQueryParameter("genre[]", genre)
                            }
                        }
                    }
                    is SortByFilter -> {
                        orderBy = filter.toUriPart()
                        url.addQueryParameter("orderby", orderBy)
                    }
                    is ProjectFilter -> {
                        if (filter.toUriPart() == "project-filter-on") {
                            url = "$baseUrl/project-list/page/$page".toHttpUrlOrNull()!!.newBuilder()
                        }
                    }
                }
            }
            url.toString()
        }
        return GET(url, headers)
    }

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.select("a").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.select(".list-update_item-info h3.title").text()
            manga.thumbnail_url = element.select("div.list-update_item-image img").imgAttr()
        }
        return manga
    }

    override fun mangaDetailsParse(document: Document): SManga {
        return SManga.create().apply {
            document.select("div.komik_info").firstOrNull()?.let { infoElement ->
                genre = infoElement.select(".komik_info-content-genre a").joinToString { it.text() }
                status = parseStatus(infoElement.select("span:contains(Status:)").firstOrNull()?.ownText())
                author = infoElement.select("span:contains(Author:)").firstOrNull()?.ownText()
                artist = infoElement.select("span:contains(Author:)").firstOrNull()?.ownText()
                description = infoElement.select("div.komik_info-description-sinopsis p").joinToString("\n") { it.text() }
                thumbnail_url = infoElement.select("div.komik_info-content-thumbnail img").imgAttr()

                // add series type(manga/manhwa/manhua/other) thinggy to genre
                document.select(seriesTypeSelector).firstOrNull()?.ownText()?.let {
                    if (it.isEmpty().not() && genre!!.contains(it, true).not()) {
                        genre += if (genre!!.isEmpty()) it else ", $it"
                    }
                }

                // add alternative name to manga description
                document.select(altNameSelector).firstOrNull()?.ownText()?.let {
                    if (it.isBlank().not() && it != "N/A" && it != "-") {
                        description = when {
                            description.isNullOrBlank() -> altName + it
                            else -> description + "\n\n$altName" + it
                        }
                    }
                }
            }
        }
    }

    override val seriesTypeSelector = "span:contains(Type) a"
    override val altNameSelector = ".komik_info-content-native"

    override fun chapterListSelector() = "div.komik_info-chapters li"

    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select("a").first()
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.attr("href"))
        chapter.name = urlElement.text()
        chapter.date_upload = element.select(".chapter-link-time").firstOrNull()?.text()?.let { parseChapterDate(it) } ?: 0
        return chapter
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select("div#chapter_body .main-reading-area img.size-full")
            .mapIndexed { i, img -> Page(i, "", img.attr("abs:Src")) }
    }

    override fun getFilterList() = FilterList(
        Filter.Header("NOTE: Ignored if using text search!"),
        Filter.Separator(),
        SortByFilter(),
        Filter.Separator(),
        StatusFilter(),
        Filter.Separator(),
        GenreListFilter(getGenreList()),
        Filter.Header("NOTE: cant be used with other filter!"),
        Filter.Header("$name Project List page"),
        ProjectFilter()
    )
}
