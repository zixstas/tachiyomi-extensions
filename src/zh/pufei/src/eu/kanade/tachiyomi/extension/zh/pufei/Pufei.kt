package eu.kanade.tachiyomi.extension.zh.pufei

// temp patch:
// https://github.com/tachiyomiorg/tachiyomi/pull/2031

import android.util.Base64
import com.squareup.duktape.Duktape
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

fun asJsoup(response: Response, html: String? = null): Document {
    return Jsoup.parse(html ?: bodyWithAutoCharset(response), response.request.url.toString())
}

fun bodyWithAutoCharset(response: Response, _charset: String? = null): String {
    val htmlBytes: ByteArray = response.body!!.bytes()
    var c = _charset

    if (c == null) {
        val regexPat = Regex("""charset=(\w+)""")
        val match = regexPat.find(String(htmlBytes))
        c = match?.groups?.get(1)?.value
    }

    return String(htmlBytes, charset(c ?: "utf8"))
}

// patch finish

fun ByteArray.toHexString() = joinToString("%") { "%02x".format(it) }

class Pufei : ParsedHttpSource() {

    override val name = "扑飞漫画"
    override val baseUrl = "http://m.pufei8.com"
    override val lang = "zh"
    override val supportsLatest = true
    val imageServer = "http://res.img.shengda0769.com/"

    override val client: OkHttpClient
        get() = network.client.newBuilder()
            .addNetworkInterceptor(rewriteOctetStream)
            .build()

    private val rewriteOctetStream: Interceptor = Interceptor { chain ->
        val originalResponse: Response = chain.proceed(chain.request())
        if (originalResponse.headers("Content-Type").contains("application/octet-stream") && originalResponse.request.url.toString().contains(".jpg")) {
            val orgBody = originalResponse.body!!.bytes()
            val newBody = orgBody.toResponseBody("image/jpeg".toMediaTypeOrNull())
            originalResponse.newBuilder()
                .body(newBody)
                .build()
        } else originalResponse
    }

    override fun popularMangaSelector() = "ul#detail li"

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/manhua/paihang.html", headers)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/manhua/update.html", headers)

    private fun mangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.select("a").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.select("h3").text().trim()
            manga.thumbnail_url = it.select("div.thumb img").attr("data-src")
        }
        return manga
    }

    override fun popularMangaFromElement(element: Element) = mangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element) = mangaFromElement(element)
    override fun searchMangaFromElement(element: Element) = mangaFromElement(element)

    override fun popularMangaNextPageSelector() = null

    override fun latestUpdatesNextPageSelector() = null

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div.book-detail")

        val manga = SManga.create()
        manga.description = infoElement.select("div#bookIntro > p").text().trim()
        manga.thumbnail_url = infoElement.select("div.thumb > img").first()?.attr("src")
        manga.author = infoElement.select(":nth-child(4) dd").first()?.text()
        return manga
    }

    override fun searchMangaNextPageSelector() = throw UnsupportedOperationException("Not used")

    override fun searchMangaSelector() = "ul#detail > li"

    private fun encodeGBK(str: String) = "%" + str.toByteArray(charset("gb2312")).toHexString()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = ("$baseUrl/e/search/?searchget=1&tbname=mh&show=title,player,playadmin,bieming,pinyin,playadmin&tempid=4&keyboard=" + encodeGBK(query)).toHttpUrlOrNull()
            ?.newBuilder()
        return GET(url.toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
//        val document = response.asJsoup()
        val document = asJsoup(response)
        val mangas = document.select(searchMangaSelector()).map { element ->
            searchMangaFromElement(element)
        }

        return MangasPage(mangas, false)
    }

    override fun chapterListSelector() = "div.chapter-list > ul > li"

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select("a")

        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.attr("href"))
        chapter.name = urlElement.text().trim()
        return chapter
    }

    override fun mangaDetailsRequest(manga: SManga) = GET(baseUrl + manga.url, headers)

    override fun pageListRequest(chapter: SChapter) = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(document: Document): List<Page> {
        val html = document.html()
        val re = Regex("cp=\"(.*?)\"")
        val imgbase64 = re.find(html)?.groups?.get(1)?.value
        val imgCode = String(Base64.decode(imgbase64, Base64.DEFAULT))
        val imgArrStr = Duktape.create().use {
            it.evaluate("$imgCode.join('|')") as String
        }
        val hasHost = imgArrStr.startsWith("http")
        return imgArrStr.split('|').mapIndexed { i, imgStr ->
            Page(i, "", if (hasHost) imgStr else imageServer + imgStr)
        }
    }

    override fun imageUrlParse(document: Document) = ""

    private class GenreFilter(genres: Array<String>) : Filter.Select<String>("Genre", genres)

    override fun getFilterList() = FilterList(
        GenreFilter(getGenreList())
    )

    private fun getGenreList() = arrayOf(
        "All"
    )

    // temp patch
    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = asJsoup(response)

        val mangas = document.select(latestUpdatesSelector()).map { element ->
            latestUpdatesFromElement(element)
        }

        return MangasPage(mangas, false)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = asJsoup(response)

        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }

        return MangasPage(mangas, false)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        return mangaDetailsParse(asJsoup(response))
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = asJsoup(response)
        return document.select(chapterListSelector()).map { chapterFromElement(it) }
    }

    override fun pageListParse(response: Response): List<Page> {
        return pageListParse(asJsoup(response))
    }

    override fun imageUrlParse(response: Response): String {
        return imageUrlParse(asJsoup(response))
    }
    // patch finish
}
