package eu.kanade.tachiyomi.extension.pt.argosscan

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class ArgosScan : HttpSource(), ConfigurableSource {

    // Website changed from Madara to a custom CMS.
    override val versionId = 2

    override val name = "Argos Scan"

    override val baseUrl = "http://argosscan.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(::loginIntercept)
        .addInterceptor(RateLimitInterceptor(1, 2, TimeUnit.SECONDS))
        .build()

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private var token: String? = null

    private fun genericMangaFromObject(project: ArgosProjectDto): SManga = SManga.create().apply {
        title = project.name!!
        url = "/obras/${project.id}"
        thumbnail_url = "$baseUrl/images/${project.id}/${project.cover!!}"
    }

    override fun popularMangaRequest(page: Int): Request {
        val payload = buildPopularQueryPayload(page)

        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)

        val newHeaders = headersBuilder()
            .add("Content-Length", body.contentLength().toString())
            .add("Content-Type", body.contentType().toString())
            .build()

        return POST(GRAPHQL_URL, newHeaders, body)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = json.parseToJsonElement(response.body!!.string()).jsonObject

        if (result["errors"] != null) {
            throw Exception(REQUEST_ERROR)
        }

        val projectList = result["data"]!!.jsonObject["getProjects"]!!
            .let { json.decodeFromJsonElement<ArgosProjectListDto>(it) }

        val mangaList = projectList.projects
            .map(::genericMangaFromObject)

        val hasNextPage = projectList.currentPage < projectList.totalPages

        return MangasPage(mangaList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val payload = buildLatestQueryPayload(page)

        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)

        val newHeaders = headersBuilder()
            .add("Content-Length", body.contentLength().toString())
            .add("Content-Type", body.contentType().toString())
            .build()

        return POST(GRAPHQL_URL, newHeaders, body)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val payload = buildSearchQueryPayload(query, page)

        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)

        val newHeaders = headersBuilder()
            .add("Content-Length", body.contentLength().toString())
            .add("Content-Type", body.contentType().toString())
            .build()

        return POST(GRAPHQL_URL, newHeaders, body)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Workaround to allow "Open in browser" use the real URL.
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(mangaDetailsApiRequest(manga))
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response).apply { initialized = true }
            }
    }

    private fun mangaDetailsApiRequest(manga: SManga): Request {
        val mangaId = manga.url.substringAfter("obras/").toInt()

        val payload = buildMangaDetailsQueryPayload(mangaId)

        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)

        val newHeaders = headersBuilder()
            .add("Content-Length", body.contentLength().toString())
            .add("Content-Type", body.contentType().toString())
            .build()

        return POST(GRAPHQL_URL, newHeaders, body)
    }

    override fun mangaDetailsParse(response: Response): SManga = SManga.create().apply {
        val result = json.parseToJsonElement(response.body!!.string()).jsonObject

        if (result["errors"] != null) {
            throw Exception(REQUEST_ERROR)
        }

        val project = result["data"]!!.jsonObject["project"]!!.jsonObject
            .let { json.decodeFromJsonElement<ArgosProjectDto>(it) }

        title = project.name!!
        thumbnail_url = "$baseUrl/images/${project.id}/${project.cover!!}"
        description = project.description.orEmpty()
        author = project.authors.orEmpty().joinToString(", ")
        status = SManga.ONGOING
        genre = project.tags.orEmpty().joinToString(", ") { it.name }
    }

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsApiRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = json.parseToJsonElement(response.body!!.string()).jsonObject

        if (result["errors"] != null) {
            throw Exception(REQUEST_ERROR)
        }

        val project = result["data"]!!.jsonObject["project"]!!.jsonObject
            .let { json.decodeFromJsonElement<ArgosProjectDto>(it) }

        return project.chapters.map(::chapterFromObject)
    }

    private fun chapterFromObject(chapter: ArgosChapterDto): SChapter = SChapter.create().apply {
        name = chapter.title!!
        chapter_number = chapter.number?.toFloat() ?: -1f
        scanlator = this@ArgosScan.name
        date_upload = chapter.createAt!!.toDate()
        url = "/leitor/${chapter.id}"
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url.substringAfter("leitor/")

        val payload = buildPagesQueryPayload(chapterId)

        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)

        val newHeaders = headersBuilder()
            .add("Content-Length", body.contentLength().toString())
            .add("Content-Type", body.contentType().toString())
            .build()

        return POST(GRAPHQL_URL, newHeaders, body)
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = json.parseToJsonElement(response.body!!.string()).jsonObject

        if (result["errors"] != null) {
            throw Exception(REQUEST_ERROR)
        }

        val chapterDto = result["data"]!!.jsonObject["getChapters"]!!.jsonObject["chapters"]!!.jsonArray[0]
            .let { json.decodeFromJsonElement<ArgosChapterDto>(it) }

        val referer = "$baseUrl/leitor/${chapterDto.id}"

        return chapterDto.images.orEmpty().mapIndexed { i, page ->
            Page(i, referer, "$baseUrl/images/${chapterDto.project!!.id}/$page")
        }
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", page.url)
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val emailPref = EditTextPreference(screen.context).apply {
            key = EMAIL_PREF_KEY
            title = EMAIL_PREF_TITLE
            summary = EMAIL_PREF_SUMMARY
            setDefaultValue("")
            dialogTitle = EMAIL_PREF_TITLE
            dialogMessage = EMAIL_PREF_DIALOG

            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }

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
            summary = PASSWORD_PREF_SUMMARY
            setDefaultValue("")
            dialogTitle = PASSWORD_PREF_TITLE
            dialogMessage = PASSWORD_PREF_DIALOG

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

        screen.addPreference(emailPref)
        screen.addPreference(passwordPref)
    }

    private fun loginIntercept(chain: Interceptor.Chain): Response {
        if (chain.request().url.toString().contains("graphql").not()) {
            return chain.proceed(chain.request())
        }

        val email = preferences.getString(EMAIL_PREF_KEY, "")
        val password = preferences.getString(PASSWORD_PREF_KEY, "")

        if (!email.isNullOrEmpty() && !password.isNullOrEmpty() && token.isNullOrEmpty()) {
            val loginResponse = chain.proceed(loginRequest(email, password))

            if (!loginResponse.headers["Content-Type"].orEmpty().contains("application/json")) {
                loginResponse.close()

                throw IOException(CLOUDFLARE_ERROR)
            }

            val loginResult = json.parseToJsonElement(loginResponse.body!!.string()).jsonObject

            if (loginResult["errors"] != null) {
                loginResponse.close()

                val errorMessage = runCatching {
                    loginResult["errors"]!!.jsonArray[0].jsonObject["message"]?.jsonPrimitive?.content
                }

                throw IOException(errorMessage.getOrNull() ?: REQUEST_ERROR)
            }

            token = loginResult["data"]!!
                .jsonObject["login"]!!
                .jsonObject["token"]!!
                .jsonPrimitive.content

            loginResponse.close()
        }

        if (!token.isNullOrEmpty()) {
            val authorizedRequest = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()

            return chain.proceed(authorizedRequest)
        }

        return chain.proceed(chain.request())
    }

    private fun loginRequest(email: String, password: String): Request {
        val payload = buildLoginMutationQueryPayload(email, password)

        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)

        val newHeaders = headersBuilder()
            .add("Content-Length", body.contentLength().toString())
            .add("Content-Type", body.contentType().toString())
            .build()

        return POST(GRAPHQL_URL, newHeaders, body)
    }

    private fun String.toDate(): Long {
        return runCatching { DATE_PARSER.parse(this)?.time }
            .getOrNull() ?: 0L
    }

    companion object {
        private const val GRAPHQL_URL = "https://argosscan.com/graphql"

        private const val EMAIL_PREF_KEY = "email"
        private const val EMAIL_PREF_TITLE = "E-mail"
        private const val EMAIL_PREF_SUMMARY = "Defina o e-mail de sua conta no site."
        private const val EMAIL_PREF_DIALOG = "Deixe em branco caso o site torne o login opcional."

        private const val PASSWORD_PREF_KEY = "password"
        private const val PASSWORD_PREF_TITLE = "Senha"
        private const val PASSWORD_PREF_SUMMARY = "Defina a senha de sua conta no site."
        private const val PASSWORD_PREF_DIALOG = EMAIL_PREF_DIALOG

        private const val CLOUDFLARE_ERROR = "Falha ao contornar o Cloudflare."
        private const val REQUEST_ERROR = "Erro na requisição. Tente novamente mais tarde."

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

        private val DATE_PARSER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale("pt", "BR"))
        }
    }
}
