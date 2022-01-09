package eu.kanade.tachiyomi.extension.fr.japscan

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier

class Japscan : ConfigurableSource, ParsedHttpSource() {

    override val id: Long = 11

    override val name = "Japscan"

    override val baseUrl = "https://www.japscan.ws"

    override val lang = "fr"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    internal class JsObject(private val latch: CountDownLatch, var width: Int = 0, var height: Int = 0) {
        @JavascriptInterface
        fun passSize(widthjs: Int, ratio: Float) {
            Log.d("japscan", "wvsc js returned $widthjs, $ratio")
            width = widthjs
            height = (width.toFloat() / ratio).toInt()
            latch.countDown()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override val client: OkHttpClient = network.cloudflareClient.newBuilder().addInterceptor { chain ->
        val indicator = "&wvsc"
        val cleanupjs = "var checkExist=setInterval(function(){if(document.getElementsByTagName('CNV-VV').length){clearInterval(checkExist);var e=document.body,a=e.children;for(e.appendChild(document.getElementsByTagName('CNV-VV')[0]);'CNV-VV'!=a[0].tagName;)e.removeChild(a[0]);for(var t of[].slice.call(a[0].all_canvas))t.style.maxWidth='100%';window.android.passSize(a[0].all_canvas[0].width,a[0].all_canvas[0].width/a[0].all_canvas[0].height)}},100);"
        val request = chain.request()
        val url = request.url.toString()

        val newRequest = request.newBuilder()
            .url(url.substringBefore(indicator))
            .build()
        val response = chain.proceed(newRequest)
        if (!url.endsWith(indicator)) return@addInterceptor response
        // Webview screenshotting code
        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var height = 0
        var width = 0
        val jsinterface = JsObject(latch)
        Log.d("japscan", "init wvsc")
        handler.post {
            val webview = WebView(Injekt.get<Application>())
            webView = webview
            webview.settings.javaScriptEnabled = true
            webview.settings.domStorageEnabled = true
            webview.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            webview.settings.useWideViewPort = false
            webview.settings.loadWithOverviewMode = false
            webview.settings.userAgentString = webview.settings.userAgentString.replace("Mobile", "eliboM").replace("Android", "diordnA")
            webview.addJavascriptInterface(jsinterface, "android")
            var retries = 1
            webview.webChromeClient = object : WebChromeClient() {
                @SuppressLint("NewApi")
                override fun onProgressChanged(view: WebView, progress: Int) {
                    if (progress == 100 && retries--> 0) {
                        Log.d("japscan", "wvsc loading finished")
                        view.evaluateJavascript(cleanupjs) {}
                    }
                }
            }
            webview.loadUrl(url.replace("&wvsc", ""))
        }

        latch.await()
        width = jsinterface.width
        height = jsinterface.height
        // webView!!.isDrawingCacheEnabled = true

        webView!!.measure(width, height)
        webView!!.layout(0, 0, width, height)
        Thread.sleep(350)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        webView!!.draw(canvas)

        // val bitmap: Bitmap = webView!!.drawingCache
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        val rb = output.toByteArray().toResponseBody("image/png".toMediaTypeOrNull())
        handler.post { webView!!.destroy() }
        response.newBuilder().body(rb).build()
    }.build()

    companion object {
        val dateFormat by lazy {
            SimpleDateFormat("dd MMM yyyy", Locale.US)
        }
        private const val SHOW_SPOILER_CHAPTERS_Title = "Les chapitres en Anglais ou non traduit sont upload en tant que \" Spoilers \" sur Japscan"
        private const val SHOW_SPOILER_CHAPTERS = "JAPSCAN_SPOILER_CHAPTERS"
        private val prefsEntries = arrayOf("Montrer uniquement les chapitres traduit en Français", "Montrer les chapitres spoiler")
        private val prefsEntryValues = arrayOf("hide", "show")
    }

    private fun chapterListPref() = preferences.getString(SHOW_SPOILER_CHAPTERS, "hide")

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/mangas/", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        pageNumberDoc = document

        val mangas = document.select(popularMangaSelector()).map { element ->
            popularMangaFromElement(element)
        }
        val hasNextPage = false
        return MangasPage(mangas, hasNextPage)
    }

    override fun popularMangaNextPageSelector(): String? = null

    override fun popularMangaSelector() = "#top_mangas_week li > span"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.select("a").first().let {
            manga.setUrlWithoutDomain(it.attr("href"))
            manga.title = it.text()
            manga.thumbnail_url = "$baseUrl/imgs/${it.attr("href").replace(Regex("/$"),".jpg").replace("manga","mangas")}".toLowerCase(Locale.ROOT)
        }
        return manga
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        return GET(baseUrl, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(latestUpdatesSelector())
            .distinctBy { element -> element.select("a").attr("href") }
            .map { element ->
                latestUpdatesFromElement(element)
            }
        val hasNextPage = false
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesSelector() = "#chapters > div > h3.text-truncate"

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isEmpty()) {
            val uri = Uri.parse(baseUrl).buildUpon()
                .appendPath("mangas")
            filters.forEach { filter ->
                when (filter) {
                    is TextField -> uri.appendPath(((page - 1) + filter.state.toInt()).toString())
                    is PageList -> uri.appendPath(((page - 1) + filter.values[filter.state]).toString())
                }
            }
            return GET(uri.toString(), headers)
        } else {
            val formBody = FormBody.Builder()
                .add("search", query)
                .build()
            val searchHeaders = headers.newBuilder()
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

            try {
                val searchRequest = POST("$baseUrl/live-search/", searchHeaders, formBody)
                val searchResponse = client.newCall(searchRequest).execute()

                if (!searchResponse.isSuccessful) {
                    throw Exception("Unexpected code ${searchResponse.code}")
                }

                val jsonResult = json.parseToJsonElement(searchResponse.body!!.string()).jsonArray

                if (jsonResult.isEmpty()) {
                    Log.d("japscan", "Search not returning anything, using duckduckgo")
                    throw Exception("No data")
                }

                return searchRequest
            } catch (e: Exception) {
                // Fallback to duckduckgo if the search does not return any result
                val uri = Uri.parse("https://duckduckgo.com/lite/").buildUpon()
                    .appendQueryParameter("q", "$query site:$baseUrl/manga/")
                    .appendQueryParameter("kd", "-1")
                return GET(uri.toString(), headers)
            }
        }
    }

    override fun searchMangaNextPageSelector(): String = "li.page-item:last-child:not(li.active),.next_form .navbutton"

    override fun searchMangaSelector(): String = "div.card div.p-2, a.result-link"

    override fun searchMangaParse(response: Response): MangasPage {
        if ("live-search" in response.request.url.toString()) {
            val jsonResult = json.parseToJsonElement(response.body!!.string()).jsonArray

            val mangaList = jsonResult.map { jsonEl -> searchMangaFromJson(jsonEl.jsonObject) }

            return MangasPage(mangaList, hasNextPage = false)
        }

        return super.searchMangaParse(response)
    }

    override fun searchMangaFromElement(element: Element): SManga {
        return if (element.attr("class") == "result-link") {
            SManga.create().apply {
                title = element.text().substringAfter(" ").substringBefore(" | JapScan")
                setUrlWithoutDomain(element.attr("abs:href"))
            }
        } else {
            SManga.create().apply {
                thumbnail_url = element.select("img").attr("abs:src")
                element.select("p a").let {
                    title = it.text()
                    url = it.attr("href")
                }
            }
        }
    }

    private fun searchMangaFromJson(jsonObj: JsonObject): SManga = SManga.create().apply {
        title = jsonObj["name"]!!.jsonPrimitive.content
        url = jsonObj["url"]!!.jsonPrimitive.content
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div#main > .card > .card-body").first()

        val manga = SManga.create()
        manga.thumbnail_url = "$baseUrl/${infoElement.select(".d-flex > div.m-2:eq(0) > img").attr("src")}"

        infoElement.select(".d-flex > div.m-2:eq(1) > p.mb-2").forEachIndexed { _, el ->
            when (el.select("span").text().trim()) {
                "Auteur(s):" -> manga.author = el.text().replace("Auteur(s):", "").trim()
                "Artiste(s):" -> manga.artist = el.text().replace("Artiste(s):", "").trim()
                "Genre(s):" -> manga.genre = el.text().replace("Genre(s):", "").trim()
                "Statut:" -> manga.status = el.text().replace("Statut:", "").trim().let {
                    parseStatus(it)
                }
            }
        }
        manga.description = infoElement.select("> p").text().orEmpty()

        return manga
    }

    private fun parseStatus(status: String) = when {
        status.contains("En Cours") -> SManga.ONGOING
        status.contains("Terminé") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "#chapters_list > div.collapse > div.chapters_list" +
        if (chapterListPref() == "hide") { ":not(:has(.badge:contains(SPOILER),.badge:contains(RAW),.badge:contains(VUS)))" } else { "" }
    // JapScan sometimes uploads some "spoiler preview" chapters, containing 2 or 3 untranslated pictures taken from a raw. Sometimes they also upload full RAWs/US versions and replace them with a translation as soon as available.
    // Those have a span.badge "SPOILER" or "RAW". The additional pseudo selector makes sure to exclude these from the chapter list.

    override fun chapterFromElement(element: Element): SChapter {
        val urlElement = element.select("a").first()

        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(urlElement.attr("href"))
        chapter.name = urlElement.ownText()
        // Using ownText() doesn't include childs' text, like "VUS" or "RAW" badges, in the chapter name.
        chapter.date_upload = element.select("> span").text().trim().let { parseChapterDate(it) }
        return chapter
    }

    private fun parseChapterDate(date: String): Long {
        return try {
            dateFormat.parse(date)?.time ?: 0
        } catch (e: ParseException) {
            0L
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        // no webview screenshot needed anymore :
        // document.getElementsByTag("option").mapIndexed { i, it -> Page(i, "", baseUrl + it.attr("value") + "&wvsc") }

        val zjsurl = document.getElementsByTag("script").first { it.attr("src").contains("zjs", ignoreCase = true) }.attr("src")
        Log.d("japscan", "ZJS at $zjsurl")
        val zjs = client.newCall(GET(baseUrl + zjsurl, headers)).execute().body!!.string()
        Log.d("japscan", "webtoon, netdumping initiated")
        val pagecount = document.getElementsByTag("option").size
        val pages = ArrayList<Page>()
        val handler = Handler(Looper.getMainLooper())
        val checkNew = ArrayList<String>(pagecount)
        var maxIter = document.getElementsByTag("option").size
        var isSinglePage = false
        if ((zjs.toLowerCase(Locale.ROOT).split("new image").size - 1) == 1) {
            isSinglePage = true
            maxIter = 1
        }
        var webView: WebView? = null
        val dummyimage = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val dummystream = ByteArrayOutputStream()
        dummyimage.compress(Bitmap.CompressFormat.JPEG, 100, dummystream)
        val barrier = CyclicBarrier(2)

        for (i in 0 until maxIter) {
            handler.post {
                if (webView == null) {
                    val webview = WebView(Injekt.get<Application>())
                    webView = webview
                    webview.settings.javaScriptEnabled = true
                    webview.settings.domStorageEnabled = true
                    webview.settings.userAgentString = webview.settings.userAgentString.replace("Mobile", "eliboM").replace("Android", "diordnA")
                    webview.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            if (request.url.toString().startsWith("https://cdn.statically.io/img/c.japscan.ws/") && !checkNew.contains(request.url.toString())) {
                                checkNew.add(request.url.toString())
                                pages.add(Page(pages.size, "", request.url.toString()))
                                Log.d("japscan", "intercepted ${request.url}")
                                if (pages.size == pagecount || !isSinglePage) {
                                    barrier.await()
                                }
                                return WebResourceResponse("image/jpeg", "UTF-8", ByteArrayInputStream(dummystream.toByteArray()))
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                }
                if (isSinglePage) {
                    webView?.loadUrl(baseUrl + document.select("li[^data-]").first().dataset()["chapter-url"])
                } else {
                    webView?.loadUrl(baseUrl + document.getElementsByTag("option")[i].attr("value"))
                }
            }
            barrier.await()
        }
        handler.post { webView!!.destroy() }
        return pages
    }

    override fun imageUrlParse(document: Document): String = ""

    // Filters
    private class TextField(name: String) : Filter.Text(name)

    private class PageList(pages: Array<Int>) : Filter.Select<Int>("Page #", arrayOf(0, *pages))

    override fun getFilterList(): FilterList {
        val totalPages = pageNumberDoc?.select("li.page-item:last-child a")?.text()
        val pagelist = mutableListOf<Int>()
        return if (!totalPages.isNullOrEmpty()) {
            for (i in 0 until totalPages.toInt()) {
                pagelist.add(i + 1)
            }
            FilterList(
                Filter.Header("Page alphabétique"),
                PageList(pagelist.toTypedArray())
            )
        } else FilterList(
            Filter.Header("Page alphabétique"),
            TextField("Page #"),
            Filter.Header("Appuyez sur reset pour la liste")
        )
    }

    private var pageNumberDoc: Document? = null

    // Prefs
    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val chapterListPref = androidx.preference.ListPreference(screen.context).apply {
            key = SHOW_SPOILER_CHAPTERS_Title
            title = SHOW_SPOILER_CHAPTERS_Title
            entries = prefsEntries
            entryValues = prefsEntryValues
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(SHOW_SPOILER_CHAPTERS, entry).commit()
            }
        }
        screen.addPreference(chapterListPref)
    }
}
