package eu.kanade.tachiyomi.extension.zh.jinmantiantang

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.ratelimit.SpecificHostRateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.floor

class Jinmantiantang : ConfigurableSource, ParsedHttpSource() {

    override val lang: String = "zh"
    override val name: String = "禁漫天堂"
    override val supportsLatest: Boolean = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl: String = "https://" +
        SITE_ENTRIES_ARRAY[preferences.getString(USE_MIRROR_URL_PREF, "0")!!.toInt()]
    private val baseHttpUrl: HttpUrl = baseUrl.toHttpUrlOrNull()!!

    // Add rate limit to fix manga thumbnail load failure
    private val mainSiteRateLimitInterceptor = SpecificHostRateLimitInterceptor(baseHttpUrl, preferences.getString(MAINSITE_RATELIMIT_PREF, "1")!!.toInt(), preferences.getString(MAINSITE_RATELIMIT_PERIOD, "3")!!.toLong())

    // 220980
    // 算法 html页面 1800 行左右
    // 图片开始分割的ID编号
    private val scrambleId = 220980

    // 处理URL请求
    override val client: OkHttpClient = network.cloudflareClient
        .newBuilder()
        .addNetworkInterceptor(mainSiteRateLimitInterceptor)
//        .addNetworkInterceptor(RateLimitInterceptor(1, 3))
        .addInterceptor(
            fun(chain): Response {
                val url = chain.request().url.toString()
                val response = chain.proceed(chain.request())
                if (!url.contains("media/photos", ignoreCase = true)) return response // 对非漫画图片连接直接放行
                if (url.substring(url.indexOf("photos/") + 7, url.lastIndexOf("/")).toInt() < scrambleId) return response // 对在漫画章节ID为220980之前的图片未进行图片分割,直接放行
// 章节ID:220980(包含)之后的漫画(2020.10.27之后)图片进行了分割getRows倒序处理
                val aid = url.substring(url.indexOf("photos/") + 7, url.lastIndexOf("/")).toInt()
                val imgIndex: String = url.substringAfterLast("/").substringBefore(".")
                val res = response.body!!.byteStream().use {
                    decodeImage(it, getRows(aid, imgIndex))
                }
                val mediaType = "image/avif,image/webp,image/apng,image/*,*/*".toMediaTypeOrNull()
                val outputBytes = res.toResponseBody(mediaType)
                return response.newBuilder().body(outputBytes).build()
            }
        ).build()

    private fun getRows(aid: Int, imgIndex: String): Int {
        fun md5(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            return BigInteger(1, md.digest(input.toByteArray())).toString(16).padStart(32, '0')
        }

        return if (aid >= 268850) {
            2 * (md5(aid.toString() + imgIndex).last().toInt() % 10) + 2
        } else {
            10
        }
    }

    // 对被分割的图片进行分割,排序处理
    private fun decodeImage(img: InputStream, rows: Int): ByteArray {
        // 使用bitmap进行图片处理
        val input = BitmapFactory.decodeStream(img)
        // 漫画高度 and width
        val height = input.height
        val width = input.width
        // 未除尽像素
        val remainder = (height % rows)
        // 创建新的图片对象
        val resultBitmap = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        // 分割图片
        for (x in 0 until rows) {
            // 分割算法(详情见html源码页的方法"function scramble_image(img)")
            var copyH = floor(height / rows.toDouble()).toInt()
            var py = copyH * (x)
            val y = height - (copyH * (x + 1)) - remainder
            if (x == 0) {
                copyH += remainder
            } else {
                py += remainder
            }
            // 要裁剪的区域
            val crop = Rect(0, y, width, y + copyH)
            // 裁剪后应放置到新图片对象的区域
            val splic = Rect(0, py, width, py + copyH)

            canvas.drawBitmap(input, crop, splic, null)
        }
        // 创建输出流
        val output = ByteArrayOutputStream()
        resultBitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
        return output.toByteArray()
    }

    // 点击量排序(人气)
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/albums?o=mv&page=$page", headers)
    }

    override fun popularMangaNextPageSelector(): String = "a.prevnext"
    override fun popularMangaSelector(): String {
        val baseSelector = ".list-col .well-sm"
        val removedGenres = preferences.getString("BLOCK_GENRES_LIST", "")!!.substringBefore("//").trim()
        // Extra selector is jquery-like selector, it uses regex to match element.text().
        // If string after 標籤 contains any word of removedGenres, the element would be ignored.
        return if (removedGenres != "")
            baseSelector + ":not(:matches((?i).*標籤: .*(${removedGenres.split(' ').joinToString("|")}).*))"
        else
            baseSelector
    }

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.select("span.video-title").text()
        setUrlWithoutDomain(element.select("a").first().attr("href"))
        thumbnail_url = element.select("img").attr("data-original").split("?")[0]
        author = element.select("div.title-truncate").select("a").first().text()
    }

    // 最新排序
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/albums?o=mr&page=$page", headers)
    }

    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()
    override fun latestUpdatesSelector(): String = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    // For JinmantiantangUrlActivity
    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/album/$id", headers)

    private fun searchMangaByIdParse(response: Response, id: String): MangasPage {
        val sManga = mangaDetailsParse(response)
        sManga.url = "/album/$id/"
        return MangasPage(listOf(sManga), false)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_ID_SEARCH)) {
            val id = query.removePrefix(PREFIX_ID_SEARCH)
            client.newCall(searchMangaByIdRequest(id))
                .asObservableSuccess()
                .map { response -> searchMangaByIdParse(response, id) }
        } else {
            super.fetchSearchManga(page, query, filters)
        }
    }

    // 查询信息
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var params = filters.map {
            if (it is UriPartFilter) {
                it.toUriPart()
            } else ""
        }.filter { it != "" }.joinToString("")

        val url = if (query != "" && !query.contains("-")) {
            // 禁漫天堂特有搜索方式: A +B --> A and B, A B --> A or B
            var newQuery = query.replace("+", "%2B").replace(" ", "+")
            // remove illegal param
            params = params.substringAfter("?")
            if (params.contains("search_query")) {
                val keyword = params.substringBefore("&").substringAfter("=")
                newQuery = "$newQuery+%2B$keyword"
                params = params.substringAfter("&")
            }
            "$baseUrl/search/photos?search_query=$newQuery&page=$page&$params".toHttpUrlOrNull()?.newBuilder()
        } else {
            params = if (params == "") "/albums?" else params
            if (query == "") {
                "$baseUrl$params&page=$page".toHttpUrlOrNull()?.newBuilder()
            } else {
                // 在搜索栏的关键词前添加-号来实现对筛选结果的过滤, 像 "-YAOI -扶他 -毛絨絨 -獵奇", 注意此时搜索功能不可用.
                val removedGenres = query.split(" ").filter { it.startsWith("-") }.joinToString("+") { it.removePrefix("-") }
                "$baseUrl$params&page=$page&screen=$removedGenres".toHttpUrlOrNull()?.newBuilder()
            }
        }
        return GET(url.toString(), headers)
    }

    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()
    override fun searchMangaSelector(): String = popularMangaSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    // 漫画详情
    // url网址 , title标题 , artist艺术家 , author作者 , description描述 , genre类型 , thumbnail_url缩图网址 , initialized是否初始化
    // status状态 0未知,1连载,2完结,3领取牌照

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.select("div.panel-heading").select("div.pull-left").first().text()
        // keep thumbnail_url same as the one in popularMangaFromElement()
        thumbnail_url = document.select("img.lazy_img.img-responsive").attr("src").split("?")[0].replace(".jpg", "_3x4.jpg")
        author = selectAuthor(document)
        artist = author
        genre = selectDetailsStatusAndGenre(document, 0).trim().split(" ").joinToString(", ")

        // When the index passed by the "selectDetailsStatusAndGenre(document: Document, index: Int)" index is 1,
        // it will definitely return a String type of 0, 1 or 2. This warning can be ignored
        status = selectDetailsStatusAndGenre(document, 1).trim().toInt()
        description = document.select("#intro-block .p-t-5.p-b-5").text().substringAfter("敘述：").trim()
    }

    // 查询作者信息
    private fun selectAuthor(document: Document): String {
        val element = document.select("div.panel-body div.tag-block")[3]
        return if (element.select("a").size == 0) {
            "未知"
        } else {
            element.select("a").text().trim().replace(" ", ", ")
        }
    }

    // 查询漫画状态和类别信息
    private fun selectDetailsStatusAndGenre(document: Document, index: Int): String {
        var status = "2"
        var genre = ""
        if (document.select("span[itemprop=genre] a").size == 0) {
            return if (index == 1) {
                status
            } else {
                genre
            }
        }
        val elements: Elements = document.select("span[itemprop=genre]").first().select("a")
        for (value in elements) {
            when (val vote: String = value.select("a").text()) {
                "連載中" -> {
                    status = "1"
                }
                else -> {
                    genre = "$genre$vote "
                }
            }
        }
        return if (index == 1) {
            status
        } else {
            genre
        }
    }

    // 漫画章节信息
    override fun chapterListSelector(): String = "div[id=episode-block] a[href^=/photo/]"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        url = element.select("a").attr("href")
        name = element.select("a li").first().ownText()
        date_upload = sdf.parse(element.select("a li span.hidden-xs").text().trim())?.time ?: 0
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        if (document.select("div[id=episode-block] a li").size == 0) {
            val singleChapter = SChapter.create().apply {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                name = "单章节"
                url = document.select("a[class=col btn btn-primary dropdown-toggle reading]").attr("href")
                date_upload = sdf.parse(document.select("div[itemprop='datePublished']").attr("content"))?.time
                    ?: 0
            }
            return listOf(singleChapter)
        }
        return document.select(chapterListSelector()).map { chapterFromElement(it) }.reversed()
    }

    // 漫画图片信息
    override fun pageListParse(document: Document): List<Page> {
        fun internalParse(document: Document, pages: MutableList<Page>): List<Page> {
            val elements = document.select("div[style=text-align:center;][id*=0]")
            for (element in elements) {
                pages.apply {
                    if (element.select("div[style=text-align:center;][id*=0] img").attr("src").indexOf("blank.jpg") >= 0) {
                        add(Page(size, "", element.select("div[style=text-align:center;][id*=0] img").attr("data-original").split("\\?")[0]))
                    } else {
                        add(Page(size, "", element.select("div[style=text-align:center;][id*=0] img").attr("src").split("\\?")[0]))
                    }
                }
            }
            return document.select("a.prevnext").firstOrNull()
                ?.let { internalParse(client.newCall(GET(it.attr("abs:href"), headers)).execute().asJsoup(), pages) }
                ?: pages
        }

        return internalParse(document, mutableListOf())
    }

    override fun imageUrlParse(document: Document): String = throw Exception("Not Used")

    // Filters
    // 按照类别信息进行检索

    override fun getFilterList() = FilterList(
        CategoryGroup(),
        SortFilter(),
        TimeFilter()
    )

    private class CategoryGroup : UriPartFilter(
        "按类型",
        arrayOf(
            Pair("全部", "/albums?"),
            Pair("其他", "/albums/another?"),
            Pair("同人", "/albums/doujin?"),
            Pair("韩漫", "/albums/hanman?"),
            Pair("美漫", "/albums/meiman?"),
            Pair("短篇", "/albums/short?"),
            Pair("单本", "/albums/single?"),
            Pair("汉化", "/albums/doujin/sub/chinese?"),
            Pair("日语", "/albums/doujin/sub/japanese?"),
            Pair("汉化", "/albums/doujin/sub/chinese?"),
            Pair("Cosplay", "/albums/doujin/sub/cosplay?"),
            Pair("CG图集", "/albums/doujin/sub/CG?"),

            Pair("P站", "/search/photos?search_query=PIXIV&"),
            Pair("3D", "/search/photos?search_query=3D&"),

            Pair("剧情", "/search/photos?search_query=劇情&"),
            Pair("校园", "/search/photos?search_query=校園&"),
            Pair("纯爱", "/search/photos?search_query=純愛&"),
            Pair("人妻", "/search/photos?search_query=人妻&"),
            Pair("师生", "/search/photos?search_query=師生&"),
            Pair("乱伦", "/search/photos?search_query=亂倫&"),
            Pair("近亲", "/search/photos?search_query=近親&"),
            Pair("百合", "/search/photos?search_query=百合&"),
            Pair("男同", "/search/photos?search_query=YAOI&"),
            Pair("性转", "/search/photos?search_query=性轉&"),
            Pair("NTR", "/search/photos?search_query=NTR&"),
            Pair("伪娘", "/search/photos?search_query=偽娘&"),
            Pair("痴女", "/search/photos?search_query=癡女&"),
            Pair("全彩", "/search/photos?search_query=全彩&"),
            Pair("女性向", "/search/photos?search_query=女性向&"),

            Pair("萝莉", "/search/photos?search_query=蘿莉&"),
            Pair("御姐", "/search/photos?search_query=御姐&"),
            Pair("熟女", "/search/photos?search_query=熟女&"),
            Pair("正太", "/search/photos?search_query=正太&"),
            Pair("巨乳", "/search/photos?search_query=巨乳&"),
            Pair("贫乳", "/search/photos?search_query=貧乳&"),
            Pair("女王", "/search/photos?search_query=女王&"),
            Pair("教师", "/search/photos?search_query=教師&"),
            Pair("女仆", "/search/photos?search_query=女僕&"),
            Pair("护士", "/search/photos?search_query=護士&"),
            Pair("泳裝", "/search/photos?search_query=泳裝&"),
            Pair("眼镜", "/search/photos?search_query=眼鏡&"),
            Pair("丝袜", "/search/photos?search_query=絲襪&"),
            Pair("连裤袜", "/search/photos?search_query=連褲襪&"),
            Pair("制服", "/search/photos?search_query=制服&"),
            Pair("兔女郎", "/search/photos?search_query=兔女郎&"),

            Pair("群交", "/search/photos?search_query=群交&"),
            Pair("足交", "/search/photos?search_query=足交&"),
            Pair("SM", "/search/photos?search_query=SM&"),
            Pair("肛交", "/search/photos?search_query=肛交&"),
            Pair("阿黑颜", "/search/photos?search_query=阿黑顏&"),
            Pair("药物", "/search/photos?search_query=藥物&"),
            Pair("扶他", "/search/photos?search_query=扶他&"),
            Pair("调教", "/search/photos?search_query=調教&"),
            Pair("野外", "/search/photos?search_query=野外&"),
            Pair("露出", "/search/photos?search_query=露出&"),
            Pair("催眠", "/search/photos?search_query=催眠&"),
            Pair("自慰", "/search/photos?search_query=自慰&"),
            Pair("触手", "/search/photos?search_query=觸手&"),
            Pair("兽交", "/search/photos?search_query=獸交&"),
            Pair("亚人", "/search/photos?search_query=亞人&"),
            Pair("魔物", "/search/photos?search_query=魔物&"),

            Pair("CG集", "/search/photos?search_query=CG集&"),
            Pair("重口", "/search/photos?search_query=重口&"),
            Pair("猎奇", "/search/photos?search_query=獵奇&"),
            Pair("非H", "/search/photos?search_query=非H&"),
            Pair("血腥", "/search/photos?search_query=血腥&"),
            Pair("暴力", "/search/photos?search_query=暴力&"),
            Pair("血腥暴力", "/search/photos?search_query=血腥暴力&")
        )
    )

    private class SortFilter : UriPartFilter(
        "排序",
        arrayOf(
            Pair("最新", "o=mr&"),
            Pair("最多浏览", "o=mv&"),
            Pair("最多爱心", "o=tf&"),
            Pair("最多图片", "o=mp&")
        )
    )

    private class TimeFilter : UriPartFilter(
        "时间",
        arrayOf(
            Pair("全部", "t=a"),
            Pair("今天", "t=t"),
            Pair("这周", "t=w"),
            Pair("本月", "t=m")
        )
    )

    /**
     *创建选择过滤器的类。 下拉菜单中的每个条目都有一个名称和一个显示名称。
     *如果选择了一个条目，它将作为查询参数附加到URI的末尾。
     *如果将firstIsUnspecified设置为true，则如果选择了第一个条目，则URI不会附加任何内容。
     */
    // vals: <name, display>
    private open class UriPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0
    ) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultValue) {
        open fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val mainSiteRateLimitPreference = androidx.preference.ListPreference(screen.context).apply {
            key = MAINSITE_RATELIMIT_PREF
            title = MAINSITE_RATELIMIT_PREF_TITLE
            entries = PREF_ENTRIES_ARRAY
            entryValues = PREF_ENTRIES_ARRAY
            summary = MAINSITE_RATELIMIT_PREF_SUMMARY

            setDefaultValue("1")
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putString(MAINSITE_RATELIMIT_PREF, newValue as String).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        val mainSiteRateLimitPeriodPreference = androidx.preference.ListPreference(screen.context).apply {
            key = MAINSITE_RATELIMIT_PERIOD
            title = MAINSITE_RATELIMIT_PERIOD_TITLE
            entries = PERIOD_ENTRIES_ARRAY
            entryValues = PERIOD_ENTRIES_ARRAY
            summary = MAINSITE_RATELIMIT_PERIOD_SUMMARY

            setDefaultValue("3")
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putString(MAINSITE_RATELIMIT_PERIOD, newValue as String).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        val mirrorURLPreference = androidx.preference.ListPreference(screen.context).apply {
            key = USE_MIRROR_URL_PREF
            title = USE_MIRROR_URL_PREF_TITLE
            entries = SITE_ENTRIES_ARRAY_DESCRIPTION
            entryValues = SITE_ENTRIES_ARRAY_VALUE
            summary = USE_MIRROR_URL_PREF_SUMMARY

            setDefaultValue("0")
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putString(USE_MIRROR_URL_PREF, newValue as String).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        EditTextPreference(screen.context).apply {
            key = "BLOCK_GENRES_LIST"
            title = BLOCK_PREF_TITLE
            setDefaultValue(BLOCK_PREF_DEFAULT)
            dialogTitle = BLOCK_PREF_DIALOGTITLE
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString("BLOCK_GENRES_LIST", newValue as String).commit()
            }
        }.let {
            screen.addPreference(it)
        }

        screen.addPreference(mainSiteRateLimitPreference)
        screen.addPreference(mainSiteRateLimitPeriodPreference)
        screen.addPreference(mirrorURLPreference)
    }

    companion object {
        private const val DEFAULT_SITE = "18comic.vip"
        const val PREFIX_ID_SEARCH = "JM:"

        private const val BLOCK_PREF_TITLE = "屏蔽词列表"
        private const val BLOCK_PREF_DEFAULT = "// 例如 \"YAOI cos 扶他 毛絨絨 獵奇 韩漫 韓漫\", " +
            "关键词之间用空格分离, 大小写不敏感, \"//\"后的字符会被忽略"
        private const val BLOCK_PREF_DIALOGTITLE = "关键词列表"

        private const val MAINSITE_RATELIMIT_PREF = "mainSiteRateLimitPreference"
        private const val MAINSITE_RATELIMIT_PREF_TITLE = "在限制时间内（下个设置项）允许的请求数量。" //  Number of requests allowed within a period of units.
        private const val MAINSITE_RATELIMIT_PREF_SUMMARY = "此值影响更新书架时发起连接请求的数量。调低此值可能减小IP被屏蔽的几率，但加载速度也会变慢。需要重启软件以生效。\n当前值：%s"

        private const val MAINSITE_RATELIMIT_PERIOD = "mainSiteRateLimitPeriodPreference"
        private const val MAINSITE_RATELIMIT_PERIOD_TITLE = "限制持续时间。单位秒" // The limiting duration. Defaults to 3.
        private const val MAINSITE_RATELIMIT_PERIOD_SUMMARY = "此值影响更新书架时请求的间隔时间。调大此值可能减小IP被屏蔽的几率，但更新时间也会变慢。需要重启软件以生效。\n当前值：%s"

        private const val USE_MIRROR_URL_PREF = "useMirrorWebsitePreference"
        private const val USE_MIRROR_URL_PREF_TITLE = "使用镜像网址"
        private const val USE_MIRROR_URL_PREF_SUMMARY = "使用镜像网址。需要重启软件以生效。" // "Use mirror url. Defaults to main site"

        private val PREF_ENTRIES_ARRAY = (1..10).map { i -> i.toString() }.toTypedArray()
        private val PERIOD_ENTRIES_ARRAY = (1..60).map { i -> i.toString() }.toTypedArray()
        private val SITE_ENTRIES_ARRAY_DESCRIPTION = arrayOf(
            "主站", "海外分流",
            "中国大陆总站", "中国大陆分流1", "中国大陆分流2"
        )
        private val SITE_ENTRIES_ARRAY_VALUE = (0..4).map { i -> i.toString() }.toTypedArray()
        // List is based on http://jmcomic.xyz
        // Please also update AndroidManifest
        private val SITE_ENTRIES_ARRAY = arrayOf(
            DEFAULT_SITE, "18comic.org",
            "18comic.art", "18comic1.art", "18comic2.art"
        )
    }
}
