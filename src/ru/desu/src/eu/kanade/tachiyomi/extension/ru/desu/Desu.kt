package eu.kanade.tachiyomi.extension.ru.desu

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import rx.Observable
import java.util.ArrayList

class Desu : HttpSource() {
    override val name = "Desu"

    override val baseUrl = "https://desu.me"

    override val lang = "ru"

    override val supportsLatest = true

    override fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", "Tachiyomi")
        add("Referer", baseUrl)
    }

    private fun mangaPageFromJSON(json: String, next: Boolean): MangasPage {
        val arr = JSONArray(json)
        val ret = ArrayList<SManga>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            ret.add(
                SManga.create().apply {
                    mangaFromJSON(obj, false)
                }
            )
        }
        return MangasPage(ret, next)
    }

    private fun SManga.mangaFromJSON(obj: JSONObject, chapter: Boolean) {
        val id = obj.getInt("id")
        url = "/$id"
        title = obj.getString("name").split(" / ").first()
        thumbnail_url = obj.getJSONObject("image").getString("original")
        val ratingValue = obj.getString("score").toFloat()
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
        val rawAgeValue = obj.getString("adult")
        val rawAgeStop = when (rawAgeValue) {
            "1" -> "18+"
            else -> "0+"
        }

        val rawTypeValue = obj.getString("kind")
        val rawTypeStr = when (rawTypeValue) {
            "manga" -> "Манга"
            "manhwa" -> "Манхва"
            "manhua" -> "Маньхуа"
            "comics" -> "Комикс"
            "one_shot" -> "Ваншот"
            else -> "Манга"
        }

        var altName = ""
        if (obj.getString("synonyms").isNotEmpty() && obj.getString("synonyms") != "null") {
            altName = "Альтернативные названия:\n" + obj.getString("synonyms").replace("|", " / ") + "\n\n"
        }
        description = obj.getString("russian") + "\n" + ratingStar + " " + ratingValue + " (голосов: " + obj.getString("score_users") + ")\n" + altName + obj.getString("description")
        genre = if (chapter) {
            val jsonArray = obj.getJSONArray("genres")
            val genreList = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                genreList.add(jsonArray.getJSONObject(i).getString("russian"))
            }
            genreList.plusElement(rawTypeStr).plusElement(rawAgeStop).joinToString()
        } else {
            obj.getString("genres") + ", " + rawTypeStr + ", " + rawAgeStop
        }
        status = when (obj.getString("status")) {
            "ongoing" -> SManga.ONGOING
            "released" -> SManga.COMPLETED
            "copyright" -> SManga.LICENSED
            else -> SManga.UNKNOWN
        }
    }

    override fun popularMangaRequest(page: Int) = GET("$baseUrl$API_URL/?limit=50&order=popular&page=$page")

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl$API_URL/?limit=50&order=updated&page=$page")

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url = "$baseUrl$API_URL/?limit=20&page=$page"
        val types = mutableListOf<Type>()
        val genres = mutableListOf<Genre>()
        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is OrderBy -> url += "&order=" + arrayOf("popular", "updated", "name")[filter.state]
                is TypeList -> filter.state.forEach { type -> if (type.state) types.add(type) }
                is GenreList -> filter.state.forEach { genre -> if (genre.state) genres.add(genre) }
            }
        }

        if (types.isNotEmpty()) {
            url += "&kinds=" + types.joinToString(",") { it.id }
        }
        if (genres.isNotEmpty()) {
            url += "&genres=" + genres.joinToString(",") { it.id }
        }
        if (query.isNotEmpty()) {
            url += "&search=$query"
        }
        return GET(url)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val res = response.body!!.string()
        val obj = JSONObject(res).getJSONArray("response")
        val nav = JSONObject(res).getJSONObject("pageNavParams")
        val count = nav.getInt("count")
        val limit = nav.getInt("limit")
        val page = nav.getInt("page")
        return mangaPageFromJSON(obj.toString(), count > page * limit)
    }

    private fun titleDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + API_URL + manga.url, headers)
    }

    // Workaround to allow "Open in browser" use the real URL.
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(titleDetailsRequest(manga))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response).apply { initialized = true }
            }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + "/manga" + manga.url, headers)
    }
    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val obj = JSONObject(response.body!!.string()).getJSONObject("response")
        mangaFromJSON(obj, true)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val obj = JSONObject(response.body!!.string()).getJSONObject("response")
        val ret = ArrayList<SChapter>()

        val cid = obj.getInt("id")

        val arr = obj.getJSONObject("chapters").getJSONArray("list")
        for (i in 0 until arr.length()) {
            val obj2 = arr.getJSONObject(i)
            ret.add(
                SChapter.create().apply {
                    val ch = obj2.getString("ch")
                    val fullnumstr = obj2.getString("vol") + ". " + "Глава " + ch
                    val title = if (obj2.getString("title") == "null") "" else obj2.getString("title")
                    name = if (title.isEmpty()) {
                        fullnumstr
                    } else {
                        "$fullnumstr: $title"
                    }
                    val id = obj2.getString("id")
                    url = "/$cid/chapter/$id"
                    chapter_number = ch.toFloat()
                    date_upload = obj2.getLong("date") * 1000
                }
            )
        }
        return ret
    }
    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + API_URL + manga.url, headers)
    }
    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + API_URL + chapter.url, headers)
    }
    override fun pageListParse(response: Response): List<Page> {
        val obj = JSONObject(response.body!!.string()).getJSONObject("response")
        val pages = obj.getJSONObject("pages")
        val list = pages.getJSONArray("list")
        val ret = ArrayList<Page>(list.length())
        for (i in 0 until list.length()) {
            ret.add(Page(i, "", list.getJSONObject(i).getString("img")))
        }
        return ret
    }

    override fun imageUrlParse(response: Response) =
        throw UnsupportedOperationException("This method should not be called!")

    private fun searchMangaByIdRequest(id: String): Request {
        return GET("$baseUrl$API_URL/$id", headers)
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
    companion object {
        const val PREFIX_SLUG_SEARCH = "slug:"
        private const val API_URL = "/manga/api"
    }

    private class OrderBy : Filter.Select<String>(
        "Сортировка",
        arrayOf("Популярность", "Дата", "Имя")
    )

    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Жанр", genres)
    private class TypeList(types: List<Type>) : Filter.Group<Type>("Тип", types)

    private class Type(name: String, val id: String) : Filter.CheckBox(name)
    private class Genre(name: String, val id: String) : Filter.CheckBox(name)
    override fun getFilterList() = FilterList(
        OrderBy(),
        TypeList(getTypeList()),
        GenreList(getGenreList())
    )

    private fun getTypeList() = listOf(
        Type("Манга", "manga"),
        Type("Манхва", "manhwa"),
        Type("Маньхуа", "manhua"),
        Type("Ваншот", "one_shot"),
        Type("Комикс", "comics")
    )

    private fun getGenreList() = listOf(
        Genre("Безумие", "Dementia"),
        Genre("Боевые искусства", "Martial Arts"),
        Genre("Вампиры", "Vampire"),
        Genre("Военное", "Military"),
        Genre("Гарем", "Harem"),
        Genre("Демоны", "Demons"),
        Genre("Детектив", "Mystery"),
        Genre("Детское", "Kids"),
        Genre("Дзёсей", "Josei"),
        Genre("Додзинси", "Doujinshi"),
        Genre("Драма", "Drama"),
        Genre("Игры", "Game"),
        Genre("Исторический", "Historical"),
        Genre("Комедия", "Comedy"),
        Genre("Космос", "Space"),
        Genre("Магия", "Magic"),
        Genre("Машины", "Cars"),
        Genre("Меха", "Mecha"),
        Genre("Музыка", "Music"),
        Genre("Пародия", "Parody"),
        Genre("Повседневность", "Slice of Life"),
        Genre("Полиция", "Police"),
        Genre("Приключения", "Adventure"),
        Genre("Психологическое", "Psychological"),
        Genre("Романтика", "Romance"),
        Genre("Самураи", "Samurai"),
        Genre("Сверхъестественное", "Supernatural"),
        Genre("Сёдзе", "Shoujo"),
        Genre("Сёдзе Ай", "Shoujo Ai"),
        Genre("Сейнен", "Seinen"),
        Genre("Сёнен", "Shounen"),
        Genre("Сёнен Ай", "Shounen Ai"),
        Genre("Смена пола", "Gender Bender"),
        Genre("Спорт", "Sports"),
        Genre("Супер сила", "Super Power"),
        Genre("Триллер", "Thriller"),
        Genre("Ужасы", "Horror"),
        Genre("Фантастика", "Sci-Fi"),
        Genre("Фэнтези", "Fantasy"),
        Genre("Хентай", "Hentai"),
        Genre("Школа", "School"),
        Genre("Экшен", "Action"),
        Genre("Этти", "Ecchi"),
        Genre("Юри", "Yuri"),
        Genre("Яой", "Yaoi")
    )
}
