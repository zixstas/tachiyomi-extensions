package eu.kanade.tachiyomi.extension.all.izneo

import android.app.Application
import android.text.InputType.TYPE_CLASS_TEXT
import android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Izneo(override val lang: String) : ConfigurableSource, HttpSource() {
    override val name = "izneo"

    override val baseUrl = "$ORIGIN/$lang/webtoon"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor(ImageInterceptor).build()

    private val apiUrl = "$ORIGIN/$lang/api/catalog/detail/webtoon"

    private val json by lazy { Injekt.get<Json>() }

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)!!
    }

    private inline val username: String
        get() = preferences.getString("username", "")!!

    private inline val password: String
        get() = preferences.getString("password", "")!!

    private val apiHeaders by lazy {
        headers.newBuilder().apply {
            set("X-Requested-With", "XMLHttpRequest")
            if (username.isNotEmpty() && password.isNotEmpty()) {
                set("Authorization", "Basic " + "$username:$password".btoa())
            }
        }.build()
    }

    private var seriesCount = 0

    override fun headersBuilder() = super.headersBuilder()
        .set("Cookie", "lang=$lang;").set("Referer", baseUrl)

    override fun latestUpdatesRequest(page: Int) =
        GET("$apiUrl/new?offset=${page - 1}&order=1&abo=0", apiHeaders)

    override fun popularMangaRequest(page: Int) =
        GET("$apiUrl/topSales?offset=${page - 1}&order=0&abo=0", apiHeaders)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        GET("$apiUrl/free?offset=${page - 1}&order=3&abo=0", apiHeaders)

    // Request the real URL for the webview
    override fun mangaDetailsRequest(manga: SManga) =
        GET(ORIGIN + manga.url, headers)

    override fun chapterListRequest(manga: SManga) =
        GET(manga.apiUrl + "/volumes/old/0/500", apiHeaders)

    override fun pageListRequest(chapter: SChapter) =
        GET(ORIGIN + "/book/" + chapter.url, apiHeaders)

    override fun imageRequest(page: Page) =
        GET(ORIGIN + "/book/" + page.imageUrl!!, apiHeaders)

    override fun latestUpdatesParse(response: Response) =
        response.parse().run {
            val count = get("series_count")!!.jsonPrimitive.int
            val series = get("series")!!.jsonObject.values.flatMap {
                json.decodeFromJsonElement<List<Series>>(it)
            }.also { seriesCount += it.size }
            if (count == seriesCount) seriesCount = 0
            series.map {
                SManga.create().apply {
                    url = it.url
                    title = it.name
                    genre = it.genres
                    author = it.authors.joinToString()
                    artist = it.authors.joinToString()
                    thumbnail_url = "$ORIGIN/$lang${it.cover}"
                    description = it.toString()
                }
            }.let { MangasPage(it, seriesCount != 0) }
        }

    override fun popularMangaParse(response: Response) =
        latestUpdatesParse(response)

    override fun searchMangaParse(response: Response) =
        latestUpdatesParse(response)

    override fun chapterListParse(response: Response) =
        response.parse()["albums"]!!.jsonArray.map {
            val album = json.decodeFromJsonElement<Album>(it)
            SChapter.create().apply {
                url = album.id
                name = album.toString()
                date_upload = album.timestamp
                chapter_number = album.number
            }
        }

    override fun pageListParse(response: Response) =
        response.parse()["data"]!!.jsonObject.run {
            val id = get("id")!!.jsonPrimitive.content
            get("pages")!!.jsonArray.map {
                val page = json.decodeFromJsonElement<AlbumPage>(it)
                Page(page.albumPageNumber, "", id + page.toString())
            }
        }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList) =
        super.fetchSearchManga(page, query, filters).map { mp ->
            mp.copy(mp.mangas.filter { it.title.contains(query, true) })
        }!!

    override fun fetchMangaDetails(manga: SManga) =
        rx.Observable.just(manga.apply { initialized = true })!!

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "username"
            title = "Username"

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = "password"
            title = "Password"

            setOnBindEditTextListener {
                it.inputType = TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD
            }

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.let(screen::addPreference)
    }

    private inline val SManga.apiUrl: String
        get() = "$ORIGIN/$lang/api/web/serie/" + url.substringAfterLast('-')

    private inline val Album.timestamp: Long
        get() = dateFormat.parse(publicationDate)?.time ?: 0L

    private fun String.btoa() = Base64.encode(toByteArray(), Base64.DEFAULT)

    private fun Response.parse() =
        json.parseToJsonElement(body!!.string()).apply {
            if (jsonObject["status"]?.jsonPrimitive?.content == "error") {
                when (jsonObject["code"]?.jsonPrimitive?.content) {
                    "4" -> throw Error("You are not authorized to view this")
                    else -> throw Error(jsonObject["data"]?.jsonPrimitive?.content)
                }
            }
        }.jsonObject

    override fun mangaDetailsParse(response: Response) =
        throw UnsupportedOperationException("Not used")

    override fun imageUrlParse(response: Response) =
        throw UnsupportedOperationException("Not used")

    companion object {
        private const val ORIGIN = "https://izneo.com"

        private val dateFormat by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        }
    }
}
