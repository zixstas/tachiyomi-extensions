package eu.kanade.tachiyomi.extension.en.toptoonplus

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
import android.util.Base64
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class TopToonPlus : HttpSource(), ConfigurableSource {

    override val name = "TOPTOON+"

    override val baseUrl = "https://toptoonplus.com"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(::authIntercept)
        .addInterceptor(RateLimitInterceptor(2, 1, TimeUnit.SECONDS))
        .build()

    private val json: Json by injectLazy()

    private val day: String
        get() = Calendar.getInstance()
            .getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.US)!!
            .toUpperCase(Locale.US)

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val email: String
        get() = preferences.getString(EMAIL_PREF_KEY, "")!!

    private val password: String
        get() = preferences.getString(PASSWORD_PREF_KEY, "")!!

    private val showMatureTitles: Boolean
        get() = preferences.getBoolean(MATURE_PREF_KEY, false)

    private val deviceId: String by lazy { UUID.randomUUID().toString() }

    private var token: String? = null
    private var userMature: Boolean = false

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request {
        val newHeaders = headersBuilder()
            .add("Accept", ACCEPT_JSON)
            .add("X-Api-Key", API_KEY)
            .build()

        return GET("$API_URL/api/v1/page/ranking", newHeaders, CacheControl.FORCE_NETWORK)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = json.decodeFromString<TopToonResult<TopToonRanking>>(response.body!!.string())

        if (result.data == null) {
            return MangasPage(emptyList(), hasNextPage = false)
        }

        val comicList = result.data.ranking.map(::popularMangaFromObject)

        return MangasPage(comicList, hasNextPage = false)
    }

    private fun popularMangaFromObject(comic: TopToonComic) = SManga.create().apply {
        title = comic.information?.title.orEmpty()
        thumbnail_url = comic.thumbnailImage?.jpeg?.firstOrNull()?.path.orEmpty()
        url = "/comic/${comic.comicId}"
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val newHeaders = headersBuilder()
            .add("Accept", ACCEPT_JSON)
            .add("X-Api-Key", API_KEY)
            .build()

        return GET("$API_URL/api/v1/page/daily/$day", newHeaders, CacheControl.FORCE_NETWORK)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val result = json.decodeFromString<TopToonResult<TopToonDaily>>(response.body!!.string())

        if (result.data == null) {
            return MangasPage(emptyList(), hasNextPage = false)
        }

        val comicList = result.data.daily.map(::latestMangaFromObject)

        return MangasPage(comicList, hasNextPage = false)
    }

    private fun latestMangaFromObject(comic: TopToonComic) = popularMangaFromObject(comic)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return super.fetchSearchManga(page, query, filters)
            .map { result ->
                val filteredList = result.mangas.filter { it.title.contains(query, true) }
                MangasPage(filteredList, result.hasNextPage)
            }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val newHeaders = headersBuilder()
            .add("Accept", ACCEPT_JSON)
            .add("Language", lang)
            .add("Mature", if (showMatureTitles) "1" else "0")
            .add("X-Api-Key", API_KEY)
            .build()

        return GET("$API_URL/api/v1/search/totalsearch", newHeaders, CacheControl.FORCE_NETWORK)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.toString().contains("ranking")) {
            return popularMangaParse(response)
        }

        val result = json.decodeFromString<TopToonResult<List<TopToonComic>>>(response.body!!.string())

        if (result.data == null) {
            return MangasPage(emptyList(), hasNextPage = false)
        }

        val comicList = result.data.map(::searchMangaFromObject)

        return MangasPage(comicList, hasNextPage = false)
    }

    private fun searchMangaFromObject(comic: TopToonComic) = popularMangaFromObject(comic)

    // Workaround to allow "Open in browser" use the real URL.
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(mangaDetailsApiRequest(manga.url))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response).apply { initialized = true }
            }
    }

    private fun mangaDetailsApiRequest(mangaUrl: String): Request {
        val newHeaders = headersBuilder()
            .add("Accept", ACCEPT_JSON)
            .add("X-Api-Key", API_KEY)
            .build()

        val comicId = mangaUrl.substringAfterLast("/")

        return GET("$API_URL/api/v1/page/episode?comicId=$comicId", newHeaders)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val newHeaders = headersBuilder()
            .removeAll("Accept")
            .build()

        return GET(baseUrl + manga.url, newHeaders)
    }

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val result = json.decodeFromString<TopToonResult<TopToonDetails>>(response.body!!.string())

        if (result.data == null) {
            throw Exception(COULD_NOT_PARSE_RESPONSE)
        }

        val comic = result.data.comic!!

        title = comic.information?.title.orEmpty()
        thumbnail_url = comic.thumbnailImage?.jpeg?.firstOrNull()?.path.orEmpty()
        description = comic.information?.description
        status = SManga.ONGOING
        author = comic.author.joinToString(", ") { it.trim() }
    }

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsApiRequest(manga.url)

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = json.decodeFromString<TopToonResult<TopToonDetails>>(response.body!!.string())

        if (result.data == null) {
            throw Exception(COULD_NOT_PARSE_RESPONSE)
        }

        return result.data.episode
            .filter { episode -> episode.information?.payType == 0 }
            .map(::chapterFromObject)
            .reversed()
    }

    private fun chapterFromObject(chapter: TopToonEpisode): SChapter = SChapter.create().apply {
        name = chapter.information?.title.orEmpty() +
            (if (chapter.information?.subTitle.isNullOrEmpty().not()) " - " + chapter.information?.subTitle else "")
        chapter_number = chapter.order.toFloat()
        scanlator = this@TopToonPlus.name
        date_upload = chapter.information?.publishedAt?.date.orEmpty().toDate()
        url = "/comic/${chapter.comicId}/${chapter.episodeId}"
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val newHeaders = headersBuilder()
            .add("Accept", ACCEPT_JSON)
            .add("Language", "en")
            .add("Token", token.orEmpty().ifEmpty { "null" })
            .add("X-Api-Key", API_KEY)
            .build()

        val comicId = chapter.url
            .substringAfter("/comic/")
            .substringBefore("/")
        val episodeId = chapter.url.substringAfterLast("/")

        val apiUrl = "$API_URL/check/isUsableEpisode".toHttpUrl().newBuilder()
            .addQueryParameter("comicId", comicId)
            .addQueryParameter("episodeId", episodeId)
            .addQueryParameter("location", "episode")
            .addQueryParameter("action", "episode_click")
            .toString()

        return GET(apiUrl, newHeaders, CacheControl.FORCE_NETWORK)
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = json.decodeFromString<TopToonResult<TopToonUsableEpisode>>(response.body!!.string())

        if (result.data == null) {
            throw Exception(COULD_NOT_PARSE_RESPONSE)
        }

        val usableEpisode = result.data

        if (usableEpisode.isFree.not() ||
            usableEpisode.episodePrice?.payType != 0 ||
            usableEpisode.purchaseMethod.firstOrNull() != "FREE_EPISODE"
        ) {
            throw Exception(CHAPTER_NOT_FREE)
        }

        return usableEpisode.episode!!.contentImage?.jpeg.orEmpty()
            .mapIndexed { i, page ->
                Page(i, baseUrl, page.path)
            }
    }

    override fun fetchImageUrl(page: Page): Observable<String> = Observable.just(page.imageUrl!!)

    override fun imageUrlParse(response: Response): String = ""

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Accept", ACCEPT_IMAGE)
            .set("Referer", page.url)
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val emailPref = EditTextPreference(screen.context).apply {
            key = EMAIL_PREF_KEY
            title = EMAIL_PREF_TITLE
            setDefaultValue("")
            summary = EMAIL_PREF_SUMMARY
            dialogTitle = EMAIL_PREF_TITLE

            setOnPreferenceChangeListener { _, newValue ->
                token = null

                preferences.edit()
                    .putString(EMAIL_PREF_KEY, newValue as String)
                    .commit()
            }
        }

        val passwordPref = EditTextPreference(screen.context).apply {
            key = PASSWORD_PREF_KEY
            title = PASSWORD_PREF_TITLE
            setDefaultValue("")
            summary = PASSWORD_PREF_SUMMARY
            dialogTitle = PASSWORD_PREF_TITLE

            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

            setOnPreferenceChangeListener { _, newValue ->
                token = null

                preferences.edit()
                    .putString(PASSWORD_PREF_KEY, newValue as String)
                    .commit()
            }
        }

        val maturePref = CheckBoxPreference(screen.context).apply {
            key = MATURE_PREF_KEY
            title = MATURE_PREF_TITLE
            setDefaultValue(MATURE_PREF_DEFAULT)
            summary = MATURE_PREF_SUMMARY

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit()
                    .putBoolean(MATURE_PREF_KEY, newValue as Boolean)
                    .commit()
            }
        }

        screen.addPreference(emailPref)
        screen.addPreference(passwordPref)
        screen.addPreference(maturePref)
    }

    private fun authIntercept(chain: Interceptor.Chain): Response {
        val isApiCall = chain.request().url.toString().contains(API_URL)

        if (isApiCall && email.isNotBlank() && password.isNotBlank()) {
            if (token == null) {
                val loginRequest = loginRequest(email, password)
                val loginResponse = chain.proceed(loginRequest)
                token = loginParse(loginResponse)

                loginResponse.close()
            }

            if (userMature != showMatureTitles && token != null) {
                // Preference takes precedence over website.
                val matureRequest = matureRequest(token!!, showMatureTitles)
                val matureResponse = chain.proceed(matureRequest)
                userMature = showMatureTitles

                matureResponse.close()
            }

            val newRequest = chain.request().newBuilder()
                .removeHeader("Token")
                .addHeader("Token", token.orEmpty().ifEmpty { "null" })
                .build()

            return chain.proceed(newRequest)
        }

        return chain.proceed(chain.request())
    }

    private fun loginRequest(email: String, password: String): Request {
        val requestPayload = buildJsonObject {
            put("auth", 0)
            put("deviceId", deviceId)
            put("is17", false)
            put("password", password)
            put("userId", email)
        }

        val requestBody = requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE)

        val newHeaders = headersBuilder()
            .add("Accept", ACCEPT_JSON)
            .add("Content-Length", requestBody.contentLength().toString())
            .add("Content-Type", requestBody.contentType().toString())
            .add("X-Api-Key", API_KEY)
            .build()

        return POST("$API_URL/auth/generateToken", newHeaders, requestBody, CacheControl.FORCE_NETWORK)
    }

    private fun loginParse(response: Response): String {
        if (response.code != 200) {
            throw IOException(COULD_NOT_LOGIN)
        }

        val result = json.decodeFromString<TopToonResult<TopToonAuth>>(response.body!!.string())

        if (result.data == null) {
            throw IOException(COULD_NOT_LOGIN)
        }

        userMature = result.data.mature == 1

        return result.data.token
    }

    private fun matureRequest(token: String, mature: Boolean): Request {
        val requestPayload = buildJsonObject {
            put("mature", if (mature) 1 else 0)
        }

        val requestBody = requestPayload.toString().toRequestBody(JSON_MEDIA_TYPE)

        val newHeaders = headersBuilder()
            .add("Accept", ACCEPT_JSON)
            .add("Content-Length", requestBody.contentLength().toString())
            .add("Content-Type", requestBody.contentType().toString())
            .add("Token", token)
            .add("Uuid", deviceId)
            .add("X-Api-Key", API_KEY)
            .build()

        return POST("$API_URL/users/setUser", newHeaders, requestBody, CacheControl.FORCE_NETWORK)
    }

    private fun String.toDate(): Long {
        return try {
            DATE_FORMATTER.parse(this)?.time ?: 0L
        } catch (e: ParseException) {
            0L
        }
    }

    companion object {
        private const val API_URL = "https://api.toptoonplus.com"
        private val API_KEY by lazy {
            Base64.decode("U1VQRVJDT09MQVBJS0VZMjAyMSNAIyg=", Base64.DEFAULT)
                .toString(charset("UTF-8"))
        }

        private const val ACCEPT_JSON = "application/json, text/plain, */*"
        private const val ACCEPT_IMAGE = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"

        private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

        private const val COULD_NOT_PARSE_RESPONSE = "Could not parse the API response."
        private const val COULD_NOT_LOGIN = "The e-mail or password provided are incorrect."
        private const val CHAPTER_NOT_FREE = "This chapter is not free to read."

        private const val EMAIL_PREF_KEY = "email"
        private const val EMAIL_PREF_TITLE = "E-mail"
        private const val EMAIL_PREF_SUMMARY = "Define here the e-mail of your existing account."

        private const val PASSWORD_PREF_KEY = "password"
        private const val PASSWORD_PREF_TITLE = "Password"
        private const val PASSWORD_PREF_SUMMARY = "Define here your account password."

        private const val MATURE_PREF_KEY = "mature"
        private const val MATURE_PREF_TITLE = "Show mature titles"
        private const val MATURE_PREF_SUMMARY = "This setting only takes effect if you are signed in."
        private const val MATURE_PREF_DEFAULT = false

        private val DATE_FORMATTER by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH) }
    }
}
