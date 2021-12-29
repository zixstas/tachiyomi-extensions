package eu.kanade.tachiyomi.extension.all.mangaplus

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.lib.ratelimit.SpecificHostRateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

abstract class MangaPlus(
    override val lang: String,
    private val internalLang: String,
    private val langCode: Language
) : HttpSource(), ConfigurableSource {

    final override val name = "MANGA Plus by SHUEISHA"

    final override val baseUrl = "https://mangaplus.shueisha.co.jp"

    final override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Origin", baseUrl)
        .add("Referer", baseUrl)
        .add("User-Agent", USER_AGENT)
        .add("Session-Token", UUID.randomUUID().toString())

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(::imageIntercept)
        .addInterceptor(::thumbnailIntercept)
        .addInterceptor(SpecificHostRateLimitInterceptor(API_URL.toHttpUrl(), 1))
        .addInterceptor(SpecificHostRateLimitInterceptor(baseUrl.toHttpUrl(), 2))
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val imageQuality: String
        get() = preferences.getString("${QUALITY_PREF_KEY}_$lang", QUALITY_PREF_DEFAULT_VALUE)!!

    private val splitImages: Boolean
        get() = preferences.getBoolean("${SPLIT_PREF_KEY}_$lang", SPLIT_PREF_DEFAULT_VALUE)

    private var titleList: List<Title>? = null

    override fun popularMangaRequest(page: Int): Request {
        val newHeaders = headersBuilder()
            .set("Referer", "$baseUrl/manga_list/hot")
            .build()

        return GET("$API_URL/title_list/ranking", newHeaders)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.asProto()

        if (result.success == null)
            throw Exception(result.error!!.langPopup.body)

        titleList = result.success.titleRankingView!!.titles
            .filter { it.language == langCode }

        val mangas = titleList!!.map {
            SManga.create().apply {
                title = it.name
                thumbnail_url = it.portraitImageUrl
                url = "#/titles/${it.titleId}"
            }
        }

        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val newHeaders = headersBuilder()
            .set("Referer", "$baseUrl/updates")
            .build()

        return GET("$API_URL/web/web_homeV3?lang=$internalLang", newHeaders)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val result = response.asProto()

        if (result.success == null)
            throw Exception(result.error!!.langPopup.body)

        // Fetch all titles to get newer thumbnail URLs in the interceptor.
        val popularResponse = client.newCall(popularMangaRequest(1)).execute().asProto()

        if (popularResponse.success != null) {
            titleList = popularResponse.success.titleRankingView!!.titles
                .filter { it.language == langCode }
        }

        val mangas = result.success.webHomeViewV3!!.groups
            .flatMap(UpdatedTitleV2Group::titleGroups)
            .flatMap(OriginalTitleGroup::titles)
            .map(UpdatedTitle::title)
            .filter { it.language == langCode }
            .map {
                SManga.create().apply {
                    title = it.name
                    thumbnail_url = it.portraitImageUrl
                    url = "#/titles/${it.titleId}"
                }
            }
            .distinctBy(SManga::title)

        return MangasPage(mangas, false)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return super.fetchSearchManga(page, query, filters)
            .map {
                if (it.mangas.size == 1) {
                    return@map it
                }

                val filteredResult = it.mangas.filter { manga ->
                    manga.title.contains(query, true)
                }

                MangasPage(filteredResult, it.hasNextPage)
            }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.startsWith(PREFIX_ID_SEARCH) && query.matches(ID_SEARCH_PATTERN)) {
            return titleDetailsRequest(query.removePrefix(PREFIX_ID_SEARCH))
        }

        val newHeaders = headersBuilder()
            .set("Referer", "$baseUrl/manga_list/all")
            .build()

        return GET("$API_URL/title_list/allV2", newHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val result = response.asProto()

        if (result.success == null)
            throw Exception(result.error!!.langPopup.body)

        if (result.success.titleDetailView != null) {
            val mangaPlusTitle = result.success.titleDetailView.title

            if (mangaPlusTitle.language == langCode) {
                val manga = SManga.create().apply {
                    title = mangaPlusTitle.name
                    thumbnail_url = mangaPlusTitle.portraitImageUrl
                    url = "#/titles/${mangaPlusTitle.titleId}"
                }

                return MangasPage(listOf(manga), hasNextPage = false)
            }

            return MangasPage(emptyList(), hasNextPage = false)
        }

        titleList = result.success.allTitlesViewV2!!.allTitlesGroup
            .flatMap(AllTitlesGroup::titles)
            .filter { it.language == langCode }

        val mangas = titleList!!.map {
            SManga.create().apply {
                title = it.name
                thumbnail_url = it.portraitImageUrl
                url = "#/titles/${it.titleId}"
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    private fun titleDetailsRequest(mangaUrl: String): Request {
        val titleId = mangaUrl.substringAfterLast("/")

        val newHeaders = headersBuilder()
            .set("Referer", "$baseUrl/titles/$titleId")
            .build()

        return GET("$API_URL/title_detail?title_id=$titleId", newHeaders)
    }

    // Workaround to allow "Open in browser" use the real URL.
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(titleDetailsRequest(manga.url))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response).apply { initialized = true }
            }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        // Remove the '#' and map to the new url format used in website.
        return GET(baseUrl + manga.url.substring(1), headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = response.asProto()

        if (result.success == null)
            throw Exception(result.error!!.langPopup.body)

        val details = result.success.titleDetailView!!
        val title = details.title

        return SManga.create().apply {
            author = title.author.replace(" / ", ", ")
            artist = author
            description = details.overview + "\n\n" + details.viewingPeriodDescription
            status = if (details.isCompleted) SManga.COMPLETED else SManga.ONGOING
            genre = details.genres.filter(String::isNotEmpty).joinToString()
            thumbnail_url = title.portraitImageUrl
        }
    }

    override fun chapterListRequest(manga: SManga): Request = titleDetailsRequest(manga.url)

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.asProto()

        if (result.success == null)
            throw Exception(result.error!!.langPopup.body)

        val titleDetailView = result.success.titleDetailView!!

        val chapters = titleDetailView.firstChapterList + titleDetailView.lastChapterList

        return chapters.reversed()
            // If the subTitle is null, then the chapter time expired.
            .filter { it.subTitle != null }
            .map {
                SChapter.create().apply {
                    name = "${it.name} - ${it.subTitle}"
                    date_upload = 1000L * it.startTimeStamp
                    url = "#/viewer/${it.chapterId}"
                    chapter_number = it.name.substringAfter("#").toFloatOrNull() ?: -1f
                }
            }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url.substringAfterLast("/")

        val newHeaders = headersBuilder()
            .set("Referer", "$baseUrl/viewer/$chapterId")
            .build()

        val url = "$API_URL/manga_viewer".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("chapter_id", chapterId)
            .addQueryParameter("split", if (splitImages) "yes" else "no")
            .addQueryParameter("img_quality", imageQuality)
            .toString()

        return GET(url, newHeaders)
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = response.asProto()

        if (result.success == null)
            throw Exception(result.error!!.langPopup.body)

        val referer = response.request.header("Referer")!!

        return result.success.mangaViewer!!.pages
            .mapNotNull(MangaPlusPage::page)
            .mapIndexed { i, page ->
                val encryptionKey = if (page.encryptionKey == null) "" else
                    "&encryptionKey=${page.encryptionKey}"
                Page(i, referer, page.imageUrl + encryptionKey)
            }
    }

    override fun fetchImageUrl(page: Page): Observable<String> = Observable.just(page.imageUrl!!)

    override fun imageUrlParse(response: Response): String = ""

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .removeAll("Origin")
            .set("Referer", page.url)
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualityPref = ListPreference(screen.context).apply {
            key = "${QUALITY_PREF_KEY}_$lang"
            title = QUALITY_PREF_TITLE
            entries = QUALITY_PREF_ENTRIES
            entryValues = QUALITY_PREF_ENTRY_VALUES
            setDefaultValue(QUALITY_PREF_DEFAULT_VALUE)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String

                preferences.edit()
                    .putString("${QUALITY_PREF_KEY}_$lang", entry)
                    .commit()
            }
        }

        val splitPref = SwitchPreferenceCompat(screen.context).apply {
            key = "${SPLIT_PREF_KEY}_$lang"
            title = SPLIT_PREF_TITLE
            summary = SPLIT_PREF_SUMMARY
            setDefaultValue(SPLIT_PREF_DEFAULT_VALUE)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean

                preferences.edit()
                    .putBoolean("${SPLIT_PREF_KEY}_$lang", checkValue)
                    .commit()
            }
        }

        screen.addPreference(qualityPref)
        screen.addPreference(splitPref)
    }

    private fun imageIntercept(chain: Interceptor.Chain): Response {
        var request = chain.request()

        if (request.url.queryParameter("encryptionKey") == null)
            return chain.proceed(request)

        val encryptionKey = request.url.queryParameter("encryptionKey")!!

        // Change the url and remove the encryptionKey to avoid detection.
        val newUrl = request.url.newBuilder()
            .removeAllQueryParameters("encryptionKey")
            .build()
        request = request.newBuilder()
            .url(newUrl)
            .build()

        val response = chain.proceed(request)

        val contentType = response.header("Content-Type", "image/jpeg")!!
        val image = decodeImage(encryptionKey, response.body!!.bytes())
        val body = image.toResponseBody(contentType.toMediaTypeOrNull())

        return response.newBuilder()
            .body(body)
            .build()
    }

    private fun decodeImage(encryptionKey: String, imageBytes: ByteArray): ByteArray {
        val keyStream = encryptionKey
            .chunked(2)
            .map { it.toInt(16) }

        return imageBytes
            .mapIndexed { i, byte -> byte.toInt() xor keyStream[i % keyStream.size] }
            .map(Int::toByte)
            .toByteArray()
    }

    private fun thumbnailIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Check if it is 404 to maintain compatibility when the extension used Weserv.
        val isBadCode = (response.code == 401 || response.code == 404)

        if (isBadCode && request.url.toString().contains(TITLE_THUMBNAIL_PATH)) {
            val titleId = request.url.toString()
                .substringBefore("/$TITLE_THUMBNAIL_PATH")
                .substringAfterLast("/")
                .toInt()
            val title = titleList?.find { it.titleId == titleId } ?: return response

            response.close()
            val thumbnailRequest = GET(title.portraitImageUrl, request.headers)
            return chain.proceed(thumbnailRequest)
        }

        return response
    }

    private val ErrorResult.langPopup: Popup
        get() = when (internalLang) {
            "esp" -> spanishPopup
            else -> englishPopup
        }

    private fun Response.asProto(): MangaPlusResponse = use {
        ProtoBuf.decodeFromByteArray(body!!.bytes())
    }

    companion object {
        private const val API_URL = "https://jumpg-webapi.tokyo-cdn.com/api"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.110 Safari/537.3"

        private const val QUALITY_PREF_KEY = "imageResolution"
        private const val QUALITY_PREF_TITLE = "Image quality"
        private val QUALITY_PREF_ENTRIES = arrayOf("Low", "Medium", "High")
        private val QUALITY_PREF_ENTRY_VALUES = arrayOf("low", "high", "super_high")
        private val QUALITY_PREF_DEFAULT_VALUE = QUALITY_PREF_ENTRY_VALUES[2]

        private const val SPLIT_PREF_KEY = "splitImage"
        private const val SPLIT_PREF_TITLE = "Split double pages"
        private const val SPLIT_PREF_SUMMARY = "Only a few titles supports disabling this setting."
        private const val SPLIT_PREF_DEFAULT_VALUE = true

        val COMPLETED_REGEX = "completado|complete|completo".toRegex()
        val REEDITION_REGEX = "revival|remasterizada".toRegex()

        private const val TITLE_THUMBNAIL_PATH = "title_thumbnail_portrait_list"

        const val PREFIX_ID_SEARCH = "id:"
        private val ID_SEARCH_PATTERN = "^id:(\\d+)$".toRegex()
    }
}
