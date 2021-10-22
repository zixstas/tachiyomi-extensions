package eu.kanade.tachiyomi.extension.ru.libmanga

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.int
import com.github.salomonbrys.kotson.nullArray
import com.github.salomonbrys.kotson.nullInt
import com.github.salomonbrys.kotson.nullString
import com.github.salomonbrys.kotson.obj
import com.github.salomonbrys.kotson.string
import com.github.salomonbrys.kotson.toMap
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class LibManga : ConfigurableSource, HttpSource() {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_${id}_2", 0x0000)
    }

    override val name: String = "Mangalib"

    override val lang = "ru"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor(RateLimitInterceptor(3))
        .build()

    private val baseOrig: String = "https://mangalib.me"
    private val baseMirr: String = "https://mangalib.org"
    private var domain: String? = preferences.getString(DOMAIN_PREF, baseOrig)
    override val baseUrl: String = domain.toString()

    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", "Mozilla/5.0 (Windows NT 6.3; WOW64)")
        add("Accept", "image/webp,*/*;q=0.8")
        add("Referer", baseUrl)
    }

    private val jsonParser = JsonParser()

    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)

    private val latestUpdatesSelector = "div.updates__item"

    override fun latestUpdatesParse(response: Response): MangasPage {
        val elements = response.asJsoup().select(latestUpdatesSelector)
        val latestMangas = elements?.map { latestUpdatesFromElement(it) }
        if (latestMangas != null)
            return MangasPage(latestMangas, false) // TODO: use API
        return MangasPage(emptyList(), false)
    }

    private fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.select("div.cover").first().let { img ->
            manga.thumbnail_url = img.attr("data-src").replace("_thumb", "_250x350")
        }

        element.select("a").first().let { link ->
            manga.setUrlWithoutDomain(link.attr("href"))
            manga.title = if (element.select(".updates__name_rus").isNullOrEmpty()) { element.select("h4").first().text() } else element.select(".updates__name_rus").first().text()
        }
        return manga
    }

    private var csrfToken: String = ""

    private fun catalogHeaders() = Headers.Builder()
        .apply {
            add("Accept", "application/json, text/plain, */*")
            add("X-Requested-With", "XMLHttpRequest")
            add("x-csrf-token", csrfToken)
        }
        .build()

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/login", headers)

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        if (csrfToken.isEmpty()) {
            return client.newCall(popularMangaRequest(page))
                .asObservableSuccess()
                .flatMap { response ->
                    // Obtain token
                    val resBody = response.body!!.string()
                    csrfToken = "_token\" content=\"(.*)\"".toRegex().find(resBody)!!.groups[1]!!.value
                    return@flatMap fetchPopularMangaFromApi(page)
                }
        }
        return fetchPopularMangaFromApi(page)
    }

    private fun fetchPopularMangaFromApi(page: Int): Observable<MangasPage> {
        return client.newCall(POST("$baseUrl/filterlist?dir=desc&sort=views&page=$page", catalogHeaders()))
            .asObservableSuccess()
            .map { response ->
                popularMangaParse(response)
            }
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val resBody = response.body!!.string()
        val result = jsonParser.parse(resBody).obj
        val items = result["items"]
        val popularMangas = items["data"].nullArray?.map { popularMangaFromElement(it) }

        if (popularMangas != null) {
            val hasNextPage = items["next_page_url"].nullString != null
            return MangasPage(popularMangas, hasNextPage)
        }
        return MangasPage(emptyList(), false)
    }

    private fun popularMangaFromElement(el: JsonElement) = SManga.create().apply {
        val slug = el["slug"].string
        val cover = el["cover"].string
        title = el["name"].string
        thumbnail_url = "$COVER_URL/uploads/cover/$slug/cover/${cover}_250x350.jpg"
        url = "/$slug"
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        if (document.select("body[data-page=home]").isNotEmpty())
            throw Exception("Can't open manga. Try log in via WebView")

        val manga = SManga.create()

        val body = document.select("div.media-info-list").first()
        val rawCategory = body.select("div.media-info-list__title:contains(Тип) + div").text()
        val category = when {
            rawCategory == "Комикс западный" -> "Комикс"
            rawCategory.isNotBlank() -> rawCategory
            else -> "Манга"
        }
        var rawAgeStop = body.select("div.media-info-list__title:contains(Возрастной рейтинг) + div").text()
        if (rawAgeStop.isEmpty()) {
            rawAgeStop = "0+"
        }

        val ratingValue = document.select(".media-rating.media-rating_lg div.media-rating__value").text().toFloat() * 2
        val ratingVotes = document.select(".media-rating.media-rating_lg div.media-rating__votes").text()
        val ratingStar = when {
            ratingValue > 9.5 -> "★★★★★"
            ratingValue > 8.5 -> "★★★★✬"
            ratingValue > 7.5 -> "★★★★☆"
            ratingValue > 6.5 -> "★★★✬☆"
            ratingValue > 5.5 -> "★★★☆☆"
            ratingValue > 4.5 -> "★★✬☆☆"
            ratingValue > 3.5 -> "★★☆☆☆"
            ratingValue > 2.5 -> "★✬☆☆☆"
            ratingValue > 1.5 -> "★☆☆☆☆"
            ratingValue > 0.5 -> "✬☆☆☆☆"
            else -> "☆☆☆☆☆"
        }
        val genres = document.select(".media-tags > a").map { it.text().capitalize() }
        manga.title = document.select(".media-name__alt").text()
        manga.thumbnail_url = document.select(".media-sidebar__cover > img").attr("src")
        manga.author = body.select("div.media-info-list__title:contains(Автор) + div").text()
        manga.artist = body.select("div.media-info-list__title:contains(Художник) + div").text()
        manga.status = if (document.html().contains("Манга удалена по просьбе правообладателей") ||
            document.html().contains("Данный тайтл лицензирован на территории РФ.")
        ) {
            SManga.LICENSED
        } else
            when (
                body.select("div.media-info-list__title:contains(Статус перевода) + div")
                    .text()
                    .toLowerCase(Locale.ROOT)
            ) {
                "продолжается" -> SManga.ONGOING
                "завершен" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        manga.genre = genres.plusElement(category).plusElement(rawAgeStop).joinToString { it.trim() }
        val altSelector = document.select(".media-info-list__item_alt-names .media-info-list__value div")
        var altName = ""
        if (altSelector.isNotEmpty()) {
            altName = "Альтернативные названия:\n" + altSelector.map { it.text() }.joinToString(" / ") + "\n\n"
        }
        manga.description = document.select(".media-name__main").text() + "\n" + ratingStar + " " + ratingValue + " (голосов: " + ratingVotes + ")\n" + altName + document.select(".media-description__text").text()
        return manga
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        if (document.html().contains("Манга удалена по просьбе правообладателей") ||
            document.html().contains("Данный тайтл лицензирован на территории РФ.")
        ) {
            return emptyList()
        }
        val dataStr = document
            .toString()
            .substringAfter("window.__DATA__ = ")
            .substringBefore("window._SITE_COLOR_")
            .substringBeforeLast(";")

        val data = jsonParser.parse(dataStr).obj
        val chaptersList = data["chapters"]["list"].nullArray
        val slug = data["manga"]["slug"].string
        val branches = data["chapters"]["branches"].array.reversed()
        val sortingList = preferences.getString(SORTING_PREF, "ms_mixing")

        val chapters: List<SChapter>? = if (branches.isNotEmpty() && !sortingList.equals("ms_mixing")) {
            sortChaptersByTranslator(sortingList, chaptersList, slug, branches)
        } else {
            chaptersList
                ?.filter { it["status"].nullInt != 2 }
                ?.map { chapterFromElement(it, sortingList, slug) }
        }

        return chapters ?: emptyList()
    }

    private fun sortChaptersByTranslator
    (sortingList: String?, chaptersList: JsonArray?, slug: String, branches: List<JsonElement>): List<SChapter>? {
        var chapters: List<SChapter>? = null
        when (sortingList) {
            "ms_combining" -> {
                val tempChaptersList = mutableListOf<SChapter>()
                for (currentBranch in branches.withIndex()) {
                    val teamId = branches[currentBranch.index]["id"].int
                    chapters = chaptersList
                        ?.filter { it["branch_id"].nullInt == teamId && it["status"].nullInt != 2 }
                        ?.map { chapterFromElement(it, sortingList, slug, teamId, branches) }
                    chapters?.let { tempChaptersList.addAll(it) }
                }
                chapters = tempChaptersList
            }
            "ms_largest" -> {
                val sizesChaptersLists = mutableListOf<Int>()
                for (currentBranch in branches.withIndex()) {
                    val teamId = branches[currentBranch.index]["id"].int
                    val chapterSize = chaptersList
                        ?.filter { it["branch_id"].nullInt == teamId }!!.size
                    sizesChaptersLists.add(chapterSize)
                }
                val max = sizesChaptersLists.indexOfFirst { it == sizesChaptersLists.maxOrNull() ?: 0 }
                val teamId = branches[max]["id"].int

                chapters = chaptersList
                    ?.filter { it["branch_id"].nullInt == teamId && it["status"].nullInt != 2 }
                    ?.map { chapterFromElement(it, sortingList, slug, teamId, branches) }
            }
            "ms_active" -> {
                for (currentBranch in branches.withIndex()) {
                    val teams = branches[currentBranch.index]["teams"].array
                    for (currentTeam in teams.withIndex()) {
                        if (teams[currentTeam.index]["is_active"].int == 1) {
                            val teamId = branches[currentBranch.index]["id"].int
                            chapters = chaptersList
                                ?.filter { it["branch_id"].nullInt == teamId && it["status"].nullInt != 2 }
                                ?.map { chapterFromElement(it, sortingList, slug, teamId, branches) }
                            break
                        }
                    }
                }
                chapters ?: throw Exception("Активный перевод не назначен на сайте")
            }
        }

        return chapters
    }

    private fun chapterFromElement
    (chapterItem: JsonElement, sortingList: String?, slug: String, teamIdParam: Int? = null, branches: List<JsonElement>? = null): SChapter {
        val chapter = SChapter.create()

        val volume = chapterItem["chapter_volume"].int
        val number = chapterItem["chapter_number"].string
        val teamId = if (teamIdParam != null) "?bid=$teamIdParam" else ""

        val url = "$baseUrl/$slug/v$volume/c$number$teamId"

        chapter.setUrlWithoutDomain(url)

        val nameChapter = chapterItem["chapter_name"].nullString
        val fullNameChapter = "Том $volume. Глава $number"

        if (!sortingList.equals("ms_mixing")) {
            chapter.scanlator = branches?.let { getScanlatorTeamName(it, chapterItem) } ?: chapterItem["username"].string
        }
        chapter.name = if (nameChapter.isNullOrBlank()) fullNameChapter else "$fullNameChapter - $nameChapter"
        chapter.date_upload = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .parse(chapterItem["chapter_created_at"].string.substringBefore(" "))?.time ?: 0L

        return chapter
    }

    private fun getScanlatorTeamName(branches: List<JsonElement>, chapterItem: JsonElement): String? {
        var scanlatorData: String? = null
        for (currentBranch in branches.withIndex()) {
            val branch = branches[currentBranch.index]
            val teams = branch["teams"].array
            if (chapterItem["branch_id"].int == branch["id"].int) {
                for (currentTeam in teams.withIndex()) {
                    val team = teams[currentTeam.index]
                    val scanlatorId = chapterItem["chapter_scanlator_id"].int
                    scanlatorData = if ((scanlatorId == team["id"].int) ||
                        (scanlatorId == 0 && team["is_active"].int == 1)
                    ) team["name"].string else branch["teams"][0]["name"].string
                }
            }
        }
        return scanlatorData
    }

    override fun prepareNewChapter(chapter: SChapter, manga: SManga) {
        """Глава\s(\d+)""".toRegex().find(chapter.name)?.let {
            val number = it.groups[1]?.value!!
            chapter.chapter_number = number.toFloat()
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        val redirect = document.html()
        if (!redirect.contains("window.__info")) {
            if (redirect.contains("hold-transition login-page")) {
                throw Exception("Для просмотра 18+ контента необходима авторизация через WebView")
            } else if (redirect.contains("header__logo")) {
                throw Exception("Лицензировано - Главы не доступны")
            }
        }

        val chapInfo = document
            .select("script:containsData(window.__info)")
            .first()
            .html()
            .split("window.__info = ")
            .last()
            .trim()
            .split(";")
            .first()

        val chapInfoJson = jsonParser.parse(chapInfo).obj
        val servers = chapInfoJson["servers"].asJsonObject.toMap()
        val defaultServer: String = chapInfoJson["img"]["server"].string
        val autoServer = setOf("secondary", "fourth", defaultServer, "compress")
        val imgUrl: String = chapInfoJson["img"]["url"].string

        val serverToUse = when (this.server) {
            null -> autoServer
            "auto" -> autoServer
            else -> listOf(this.server)
        }

        // Get pages
        val pagesArr = document
            .select("script:containsData(window.__pg)")
            .first()
            .html()
            .trim()
            .removePrefix("window.__pg = ")
            .removeSuffix(";")

        val pagesJson = jsonParser.parse(pagesArr).array
        val pages = mutableListOf<Page>()

        pagesJson.forEach { page ->
            val keys = servers.keys.filter { serverToUse.indexOf(it) >= 0 }.sortedBy { serverToUse.indexOf(it) }
            val serversUrls = keys.map {
                servers[it]?.string + imgUrl + page["u"].string
            }.joinToString(separator = ",,") { it }
            pages.add(Page(page["p"].int, serversUrls))
        }

        return pages
    }

    private fun checkImage(url: String): Boolean {
        val response = client.newCall(Request.Builder().url(url).head().headers(headers).build()).execute()
        return response.isSuccessful && (response.header("content-length", "0")?.toInt()!! > 320)
    }

    override fun fetchImageUrl(page: Page): Observable<String> {
        if (page.imageUrl != null) {
            return Observable.just(page.imageUrl)
        }

        val urls = page.url.split(",,")
        if (urls.size == 1) {
            return Observable.just(urls[0])
        }

        return Observable.from(urls).filter { checkImage(it) }.first()
    }

    override fun imageUrlParse(response: Response): String = ""

    private fun searchMangaByIdRequest(id: String): Request {
        return GET("$baseUrl/$id", headers)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_SLUG_SEARCH)) {
            val realQuery = query.removePrefix(PREFIX_SLUG_SEARCH)
            client.newCall(searchMangaByIdRequest(realQuery))
                .asObservableSuccess()
                .map { response ->
                    val details = mangaDetailsParse(response)
                    details.url = "/$realQuery"
                    MangasPage(listOf(details), false)
                }
        } else {
            client.newCall(searchMangaRequest(page, query, filters))
                .asObservableSuccess()
                .map { response ->
                    searchMangaParse(response)
                }
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (csrfToken.isEmpty()) {
            val tokenResponse = client.newCall(popularMangaRequest(page)).execute()
            val resBody = tokenResponse.body!!.string()
            csrfToken = "_token\" content=\"(.*)\"".toRegex().find(resBody)!!.groups[1]!!.value
        }
        val url = "$baseUrl/filterlist?page=$page".toHttpUrlOrNull()!!.newBuilder()
        if (query.isNotEmpty()) {
            url.addQueryParameter("name", query)
        }
        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is CategoryList -> filter.state.forEach { category ->
                    if (category.state) {
                        url.addQueryParameter("types[]", category.id)
                    }
                }
                is FormatList -> filter.state.forEach { forma ->
                    if (forma.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(if (forma.isIncluded()) "format[include][]" else "format[exclude][]", forma.id)
                    }
                }
                is StatusList -> filter.state.forEach { status ->
                    if (status.state) {
                        url.addQueryParameter("status[]", status.id)
                    }
                }
                is StatusTitleList -> filter.state.forEach { title ->
                    if (title.state) {
                        url.addQueryParameter("manga_status[]", title.id)
                    }
                }
                is GenreList -> filter.state.forEach { genre ->
                    if (genre.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(if (genre.isIncluded()) "genres[include][]" else "genres[exclude][]", genre.id)
                    }
                }
                is OrderBy -> {
                    url.addQueryParameter("dir", if (filter.state!!.ascending) "asc" else "desc")
                    url.addQueryParameter("sort", arrayOf("rate", "name", "views", "created_at", "last_chapter_at", "chap_count")[filter.state!!.index])
                }
                is AgeList -> filter.state.forEach { age ->
                    if (age.state) {
                        url.addQueryParameter("caution[]", age.id)
                    }
                }
                is TagList -> filter.state.forEach { tag ->
                    if (tag.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(if (tag.isIncluded()) "tags[include][]" else "tags[exclude][]", tag.id)
                    }
                }
            }
        }
        return POST(url.toString(), catalogHeaders())
    }

    // Hack search method to add some results from search popup
    override fun searchMangaParse(response: Response): MangasPage {
        val searchRequest = response.request.url.queryParameter("name")
        val mangas = mutableListOf<SManga>()

        if (!searchRequest.isNullOrEmpty()) {
            val popupSearchHeaders = headers
                .newBuilder()
                .add("Accept", "application/json, text/plain, */*")
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

            // +200ms
            val popup = client.newCall(
                GET("$baseUrl/search?query=$searchRequest", popupSearchHeaders)
            )
                .execute().body!!.string()

            val jsonList = jsonParser.parse(popup).array
            jsonList.forEach {
                mangas.add(popularMangaFromElement(it))
            }
        }
        val searchedMangas = popularMangaParse(response)

        // Filtered out what find in popup search
        mangas.addAll(
            searchedMangas.mangas.filter { search ->
                mangas.find { search.title == it.title } == null
            }
        )

        return MangasPage(mangas, searchedMangas.hasNextPage)
    }

    private class SearchFilter(name: String, val id: String) : Filter.TriState(name)
    private class CheckFilter(name: String, val id: String) : Filter.CheckBox(name)

    private class CategoryList(categories: List<CheckFilter>) : Filter.Group<CheckFilter>("Тип", categories)
    private class FormatList(formas: List<SearchFilter>) : Filter.Group<SearchFilter>("Формат выпуска", formas)
    private class StatusList(statuses: List<CheckFilter>) : Filter.Group<CheckFilter>("Статус перевода", statuses)
    private class StatusTitleList(titles: List<CheckFilter>) : Filter.Group<CheckFilter>("Статус тайтла", titles)
    private class GenreList(genres: List<SearchFilter>) : Filter.Group<SearchFilter>("Жанры", genres)
    private class TagList(tags: List<SearchFilter>) : Filter.Group<SearchFilter>("Теги", tags)
    private class AgeList(ages: List<CheckFilter>) : Filter.Group<CheckFilter>("Возрастное ограничение", ages)

    override fun getFilterList() = FilterList(
        OrderBy(),
        CategoryList(getCategoryList()),
        FormatList(getFormatList()),
        GenreList(getGenreList()),
        TagList(getTagList()),
        StatusList(getStatusList()),
        StatusTitleList(getStatusTitleList()),
        AgeList(getAgeList())
    )

    private class OrderBy : Filter.Sort(
        "Сортировка",
        arrayOf("Рейтинг", "Имя", "Просмотры", "Дате добавления", "Дате обновления", "Кол-во глав"),
        Selection(0, false)
    )

    /*
    * Use console
    * Object.entries(__FILTER_ITEMS__.types).map(([k, v]) => `SearchFilter("${v.label}", "${v.id}")`).join(',\n')
    * on /manga-list
    */
    private fun getCategoryList() = listOf(
        CheckFilter("Манга", "1"),
        CheckFilter("OEL-манга", "4"),
        CheckFilter("Манхва", "5"),
        CheckFilter("Маньхуа", "6"),
        CheckFilter("Руманга", "8"),
        CheckFilter("Комикс западный", "9")
    )

    private fun getFormatList() = listOf(
        SearchFilter("4-кома (Ёнкома)", "1"),
        SearchFilter("Сборник", "2"),
        SearchFilter("Додзинси", "3"),
        SearchFilter("Сингл", "4"),
        SearchFilter("В цвете", "5"),
        SearchFilter("Веб", "6")
    )

    /*
    * Use console
    * Object.entries(__FILTER_ITEMS__.status).map(([k, v]) => `SearchFilter("${v.label}", "${v.id}")`).join(',\n')
    * on /manga-list
    */
    private fun getStatusList() = listOf(
        CheckFilter("Продолжается", "1"),
        CheckFilter("Завершен", "2"),
        CheckFilter("Заморожен", "3"),
        CheckFilter("Заброшен", "4")
    )

    private fun getStatusTitleList() = listOf(
        CheckFilter("Онгоинг", "1"),
        CheckFilter("Завершён", "2"),
        CheckFilter("Анонс", "3"),
        CheckFilter("Приостановлен", "4"),
        CheckFilter("Выпуск прекращён", "5"),
    )

    /*
    * Use console
    * __FILTER_ITEMS__.genres.map(it => `SearchFilter("${it.name}", "${it.id}")`).join(',\n')
    * on /manga-list
    */
    private fun getGenreList() = listOf(
        SearchFilter("арт", "32"),
        SearchFilter("боевик", "34"),
        SearchFilter("боевые искусства", "35"),
        SearchFilter("вампиры", "36"),
        SearchFilter("гарем", "37"),
        SearchFilter("гендерная интрига", "38"),
        SearchFilter("героическое фэнтези", "39"),
        SearchFilter("детектив", "40"),
        SearchFilter("дзёсэй", "41"),
        SearchFilter("драма", "43"),
        SearchFilter("игра", "44"),
        SearchFilter("исекай", "79"),
        SearchFilter("история", "45"),
        SearchFilter("киберпанк", "46"),
        SearchFilter("кодомо", "76"),
        SearchFilter("комедия", "47"),
        SearchFilter("махо-сёдзё", "48"),
        SearchFilter("меха", "49"),
        SearchFilter("мистика", "50"),
        SearchFilter("научная фантастика", "51"),
        SearchFilter("омегаверс", "77"),
        SearchFilter("повседневность", "52"),
        SearchFilter("постапокалиптика", "53"),
        SearchFilter("приключения", "54"),
        SearchFilter("психология", "55"),
        SearchFilter("романтика", "56"),
        SearchFilter("самурайский боевик", "57"),
        SearchFilter("сверхъестественное", "58"),
        SearchFilter("сёдзё", "59"),
        SearchFilter("сёдзё-ай", "60"),
        SearchFilter("сёнэн", "61"),
        SearchFilter("сёнэн-ай", "62"),
        SearchFilter("спорт", "63"),
        SearchFilter("сэйнэн", "64"),
        SearchFilter("трагедия", "65"),
        SearchFilter("триллер", "66"),
        SearchFilter("ужасы", "67"),
        SearchFilter("фантастика", "68"),
        SearchFilter("фэнтези", "69"),
        SearchFilter("школа", "70"),
        SearchFilter("эротика", "71"),
        SearchFilter("этти", "72"),
        SearchFilter("юри", "73"),
        SearchFilter("яой", "74")
    )

    private fun getTagList() = listOf(
        SearchFilter("Азартные игры", "304"),
        SearchFilter("Алхимия", "225"),
        SearchFilter("Ангелы", "226"),
        SearchFilter("Антигерой", "175"),
        SearchFilter("Антиутопия", "227"),
        SearchFilter("Апокалипсис", "228"),
        SearchFilter("Армия", "229"),
        SearchFilter("Артефакты", "230"),
        SearchFilter("Боги", "215"),
        SearchFilter("Бои на мечах", "231"),
        SearchFilter("Борьба за власть", "231"),
        SearchFilter("Брат и сестра", "233"),
        SearchFilter("Будущее", "234"),
        SearchFilter("Ведьма", "338"),
        SearchFilter("Вестерн", "235"),
        SearchFilter("Видеоигры", "185"),
        SearchFilter("Виртуальная реальность", "195"),
        SearchFilter("Владыка демонов", "236"),
        SearchFilter("Военные", "179"),
        SearchFilter("Война", "237"),
        SearchFilter("Волшебники / маги", "281"),
        SearchFilter("Волшебные существа", "239"),
        SearchFilter("Воспоминания из другого мира", "240"),
        SearchFilter("Выживание", "193"),
        SearchFilter("ГГ женщина", "243"),
        SearchFilter("ГГ имба", "291"),
        SearchFilter("ГГ мужчина", "244"),
        SearchFilter("Геймеры", "241"),
        SearchFilter("Гильдии", "242"),
        SearchFilter("Глупый ГГ", "297"),
        SearchFilter("Гоблины", "245"),
        SearchFilter("Горничные", "169"),
        SearchFilter("Гяру", "178"),
        SearchFilter("Демоны", "151"),
        SearchFilter("Драконы", "246"),
        SearchFilter("Дружба", "247"),
        SearchFilter("Жестокий мир", "249"),
        SearchFilter("Животные компаньоны", "250"),
        SearchFilter("Завоевание мира", "251"),
        SearchFilter("Зверолюди", "162"),
        SearchFilter("Злые духи", "252"),
        SearchFilter("Зомби", "149"),
        SearchFilter("Игровые элементы", "253"),
        SearchFilter("Империи", "254"),
        SearchFilter("Квесты", "255"),
        SearchFilter("Космос", "256"),
        SearchFilter("Кулинария", "152"),
        SearchFilter("Культивация", "160"),
        SearchFilter("Легендарное оружие", "257"),
        SearchFilter("Лоли", "187"),
        SearchFilter("Магическая академия", "258"),
        SearchFilter("Магия", "168"),
        SearchFilter("Мафия", "172"),
        SearchFilter("Медицина", "153"),
        SearchFilter("Месть", "259"),
        SearchFilter("Монстр Девушки", "188"),
        SearchFilter("Монстры", "189"),
        SearchFilter("Музыка", "190"),
        SearchFilter("Навыки / способности", "260"),
        SearchFilter("Насилие / жестокость", "262"),
        SearchFilter("Наёмники", "261"),
        SearchFilter("Нежить", "263"),
        SearchFilter("Ниндая", "180"),
        SearchFilter("Обратный Гарем", "191"),
        SearchFilter("Огнестрельное оружие", "264"),
        SearchFilter("Офисные Работники", "181"),
        SearchFilter("Пародия", "265"),
        SearchFilter("Пираты", "340"),
        SearchFilter("Подземелья", "266"),
        SearchFilter("Политика", "267"),
        SearchFilter("Полиция", "182"),
        SearchFilter("Преступники / Криминал", "186"),
        SearchFilter("Призраки / Духи", "177"),
        SearchFilter("Путешествие во времени", "194"),
        SearchFilter("Разумные расы", "268"),
        SearchFilter("Ранги силы", "248"),
        SearchFilter("Реинкарнация", "148"),
        SearchFilter("Роботы", "269"),
        SearchFilter("Рыцари", "270"),
        SearchFilter("Самураи", "183"),
        SearchFilter("Система", "271"),
        SearchFilter("Скрытие личности", "273"),
        SearchFilter("Спасение мира", "274"),
        SearchFilter("Спортивное тело", "334"),
        SearchFilter("Средневековье", "173"),
        SearchFilter("Стимпанк", "272"),
        SearchFilter("Супергерои", "275"),
        SearchFilter("Традиционные игры", "184"),
        SearchFilter("Умный ГГ", "302"),
        SearchFilter("Учитель / ученик", "276"),
        SearchFilter("Философия", "277"),
        SearchFilter("Хикикомори", "166"),
        SearchFilter("Холодное оружие", "278"),
        SearchFilter("Шантаж", "279"),
        SearchFilter("Эльфы", "216"),
        SearchFilter("Якудза", "164"),
        SearchFilter("Япония", "280")

    )

    private fun getAgeList() = listOf(
        CheckFilter("Отсутствует", "0"),
        CheckFilter("16+", "1"),
        CheckFilter("18+", "2")
    )

    companion object {
        const val PREFIX_SLUG_SEARCH = "slug:"
        private const val SERVER_PREF = "MangaLibImageServer"
        private const val SERVER_PREF_Title = "Сервер изображений"

        private const val SORTING_PREF = "MangaLibSorting"
        private const val SORTING_PREF_Title = "Способ выбора переводчиков"

        private const val DOMAIN_PREF = "MangaLibDomain"
        private const val DOMAIN_PREF_Title = "Выбор домена"

        private const val COVER_URL = "https://staticlib.me"
    }

    private var server: String? = preferences.getString(SERVER_PREF, null)
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val serverPref = ListPreference(screen.context).apply {
            key = SERVER_PREF
            title = SERVER_PREF_Title
            entries = arrayOf("Основной", "Второй (тестовый)", "Третий (эконом трафика)", "Авто")
            entryValues = arrayOf("secondary", "fourth", "compress", "auto")
            summary = "%s"
            setDefaultValue("auto")
            setOnPreferenceChangeListener { _, newValue ->
                server = newValue.toString()
                true
            }
        }

        val sortingPref = ListPreference(screen.context).apply {
            key = SORTING_PREF
            title = SORTING_PREF_Title
            entries = arrayOf(
                "Полный список (без повторных переводов)", "Все переводы (друг за другом)",
                "Наибольшее число глав", "Активный перевод"
            )
            entryValues = arrayOf("ms_mixing", "ms_combining", "ms_largest", "ms_active")
            summary = "%s"
            setDefaultValue("ms_mixing")
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences.edit().putString(SORTING_PREF, selected).commit()
            }
        }
        val domainPref = ListPreference(screen.context).apply {
            key = DOMAIN_PREF
            title = DOMAIN_PREF_Title
            entries = arrayOf("Основной (mangalib.me)", "Зеркало (mangalib.org)")
            entryValues = arrayOf(baseOrig, baseMirr)
            summary = "%s"
            setDefaultValue(baseOrig)
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(DOMAIN_PREF, newValue as String).commit()
                    val warning = "Для смены домена необходимо перезапустить приложение с полной остановкой."
                    Toast.makeText(screen.context, warning, Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        screen.addPreference(domainPref)
        screen.addPreference(serverPref)
        screen.addPreference(sortingPref)
    }
}
