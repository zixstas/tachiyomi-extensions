package eu.kanade.tachiyomi.extension.all.mangadex

import android.util.Log
import eu.kanade.tachiyomi.extension.all.mangadex.dto.AtHomeDto
import eu.kanade.tachiyomi.extension.all.mangadex.dto.ChapterDataDto
import eu.kanade.tachiyomi.extension.all.mangadex.dto.MangaAttributesDto
import eu.kanade.tachiyomi.extension.all.mangadex.dto.MangaDataDto
import eu.kanade.tachiyomi.extension.all.mangadex.dto.asMdMap
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.parser.Parser
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MangaDexHelper() {

    val mdFilters = MangaDexFilters()

    val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        allowSpecialFloatingPointValues = true
        useArrayPolymorphism = true
        prettyPrint = true
    }

    /**
     * Gets the UUID from the url
     */
    fun getUUIDFromUrl(url: String) = url.substringAfterLast("/")

    /**
     * get chapters for manga (aka manga/$id/feed endpoint)
     */
    fun getChapterEndpoint(mangaId: String, offset: Int, langCode: String) =
        "${MDConstants.apiMangaUrl}/$mangaId/feed?includes[]=${MDConstants.scanlator}&includes[]=${MDConstants.uploader}&limit=500&offset=$offset&translatedLanguage[]=$langCode&order[volume]=desc&order[chapter]=desc"

    /**
     * Check if the manga url is a valid uuid
     */
    fun containsUuid(url: String) = url.contains(MDConstants.uuidRegex)

    /**
     * Get the manga offset pages are 1 based, so subtract 1
     */
    fun getMangaListOffset(page: Int): String = (MDConstants.mangaLimit * (page - 1)).toString()

    /**
     * Get the latest chapter offset pages are 1 based, so subtract 1
     */
    fun getLatestChapterOffset(page: Int): String = (MDConstants.latestChapterLimit * (page - 1)).toString()

    /**
     * Remove bbcode tags as well as parses any html characters in description or
     * chapter name to actual characters for example &hearts; will show ♥
     */
    fun cleanString(string: String): String {
        val bbRegex =
            """\[(\w+)[^]]*](.*?)\[/\1]""".toRegex()
        var intermediate = string
            .replace("[list]", "")
            .replace("[/list]", "")
            .replace("[*]", "")
        // Recursively remove nested bbcode
        while (bbRegex.containsMatchIn(intermediate)) {
            intermediate = intermediate.replace(bbRegex, "$2")
        }
        return Parser.unescapeEntities(intermediate, false)
    }

    /**
     * Maps dex status to tachi status
     */
    fun getPublicationStatus(attr: MangaAttributesDto, chapters: List<String>): Int {
        return when (attr.status) {
            null -> SManga.UNKNOWN
            "ongoing" -> SManga.ONGOING
            "completed", "cancelled" -> doubleCheckChapters(attr, chapters)
            "hiatus" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    /**
     * if the manga is 'completed' or 'cancelled' then it'll have a lastChapter in the manga obj.
     * if the simple list of chapters contains that lastChapter, then we can consider it completed.
     */
    private fun doubleCheckChapters(attr: MangaAttributesDto, chapters: List<String>): Int {
        return if (chapters.contains(attr.lastChapter))
            SManga.COMPLETED
        else
            SManga.UNKNOWN
    }

    fun parseDate(dateAsString: String): Long =
        MDConstants.dateFormatter.parse(dateAsString)?.time ?: 0

    // chapter url where we get the token, last request time
    private val tokenTracker = hashMapOf<String, Long>()

    companion object {
        val USE_CACHE = CacheControl.Builder()
            .maxStale(Integer.MAX_VALUE, TimeUnit.SECONDS)
            .build()
    }

    // Check the token map to see if the md@home host is still valid
    fun getValidImageUrlForPage(page: Page, headers: Headers, client: OkHttpClient): Request {
        val data = page.url.split(",")

        val mdAtHomeServerUrl =
            when (Date().time - data[2].toLong() > MDConstants.mdAtHomeTokenLifespan) {
                false -> data[0]
                true -> {
                    val tokenRequestUrl = data[1]
                    val cacheControl =
                        if (Date().time - (
                            tokenTracker[tokenRequestUrl]
                                ?: 0
                            ) > MDConstants.mdAtHomeTokenLifespan
                        ) {
                            CacheControl.FORCE_NETWORK
                        } else {
                            USE_CACHE
                        }
                    getMdAtHomeUrl(tokenRequestUrl, client, headers, cacheControl)
                }
            }
        return GET(mdAtHomeServerUrl + page.imageUrl, headers)
    }

    /**
     * get the md@home url
     */
    fun getMdAtHomeUrl(
        tokenRequestUrl: String,
        client: OkHttpClient,
        headers: Headers,
        cacheControl: CacheControl,
    ): String {
        if (cacheControl == CacheControl.FORCE_NETWORK) {
            tokenTracker[tokenRequestUrl] = Date().time
        }
        val response =
            client.newCall(GET(tokenRequestUrl, headers, cacheControl)).execute()

        // This check is for the error that causes pages to fail to load.
        // It should never be entered, but in case it is, we retry the request.
        if (response.code == 504) {
            Log.wtf("MangaDex", "Failed to read cache for \"$tokenRequestUrl\"")
            return getMdAtHomeUrl(tokenRequestUrl, client, headers, CacheControl.FORCE_NETWORK)
        }

        return json.decodeFromString<AtHomeDto>(response.body!!.string()).baseUrl
    }

    /**
     * create an SManga from json element only basic elements
     */
    fun createBasicManga(
        mangaDataDto: MangaDataDto,
        coverFileName: String?,
        coverSuffix: String?,
        lang: String
    ): SManga {
        return SManga.create().apply {
            url = "/manga/${mangaDataDto.id}"
            val titleMap = mangaDataDto.attributes.title.asMdMap()
            val dirtyTitle = titleMap[lang]
                ?: titleMap["en"]
                ?: mangaDataDto.attributes.altTitles.jsonArray
                    .find {
                        val altTitle = it.asMdMap()
                        altTitle[lang] ?: altTitle["en"] != null
                    }?.asMdMap()?.values?.singleOrNull()
                ?: titleMap["ja"] // romaji titles are sometimes ja (and are not altTitles)
                ?: titleMap.values.firstOrNull() // use literally anything from title as a last resort
            title = cleanString(dirtyTitle ?: "")

            coverFileName?.let {
                thumbnail_url = when (coverSuffix != null && coverSuffix != "") {
                    true -> "${MDConstants.cdnUrl}/covers/${mangaDataDto.id}/$coverFileName$coverSuffix"
                    else -> "${MDConstants.cdnUrl}/covers/${mangaDataDto.id}/$coverFileName"
                }
            }
        }
    }

    /**
     * Create an SManga from json element with all details
     */
    fun createManga(mangaDataDto: MangaDataDto, chapters: List<String>, lang: String, coverSuffix: String?): SManga {
        try {
            val attr = mangaDataDto.attributes

            // things that will go with the genre tags but aren't actually genre

            val tempContentRating = attr.contentRating
            val contentRating =
                if (tempContentRating == null || tempContentRating.equals("safe", true)) {
                    null
                } else {
                    "Content rating: " + tempContentRating.capitalize(Locale.US)
                }

            val dexLocale = Locale.forLanguageTag(lang)

            val nonGenres = listOf(
                (attr.publicationDemographic ?: "").capitalize(Locale.US),
                contentRating,
                Locale(attr.originalLanguage ?: "")
                    .getDisplayLanguage(dexLocale)
                    .capitalize(dexLocale)
            )

            val authors = mangaDataDto.relationships.filter { relationshipDto ->
                relationshipDto.type.equals(MDConstants.author, true)
            }.mapNotNull { it.attributes!!.name }.distinct()

            val artists = mangaDataDto.relationships.filter { relationshipDto ->
                relationshipDto.type.equals(MDConstants.artist, true)
            }.mapNotNull { it.attributes!!.name }.distinct()

            val coverFileName = mangaDataDto.relationships.firstOrNull { relationshipDto ->
                relationshipDto.type.equals(MDConstants.coverArt, true)
            }?.attributes?.fileName

            // get tag list
            val tags = mdFilters.getTags()

            // map ids to tag names
            val genreList = (
                attr.tags
                    .map { it.id }
                    .map { dexId ->
                        tags.firstOrNull { it.id == dexId }
                    }
                    .map { it?.name } +
                    nonGenres
                )
                .filter { it.isNullOrBlank().not() }

            val desc = attr.description.asMdMap()
            return createBasicManga(mangaDataDto, coverFileName, coverSuffix, lang).apply {
                description = cleanString(desc[lang] ?: desc["en"] ?: "")
                author = authors.joinToString(", ")
                artist = artists.joinToString(", ")
                status = getPublicationStatus(attr, chapters)
                genre = genreList.joinToString(", ")
            }
        } catch (e: Exception) {
            Log.e("MangaDex", "error parsing manga", e)
            throw(e)
        }
    }

    /**
     * create the SChapter from json
     */
    fun createChapter(chapterDataDto: ChapterDataDto): SChapter? {
        try {
            val attr = chapterDataDto.attributes

            val groups = chapterDataDto.relationships.filter { relationshipDto ->
                relationshipDto.type.equals(
                    MDConstants.scanlator,
                    true
                )
            }.filterNot { it.id == MDConstants.legacyNoGroupId } // 'no group' left over from MDv3
                .mapNotNull { it.attributes!!.name }
                .joinToString(" & ")
                .ifEmpty {
                    // fall back to uploader name if no group
                    val users = chapterDataDto.relationships.filter { relationshipDto ->
                        relationshipDto.type.equals(
                            MDConstants.uploader,
                            true
                        )
                    }.mapNotNull { it.attributes!!.username }
                    users.joinToString(" & ", if (users.isNotEmpty()) "Uploaded by " else "")
                }.ifEmpty { "No Group" } // "No Group" as final resort

            val chapterName = mutableListOf<String>()
            // Build chapter name

            attr.volume?.let {
                if (it.isNotEmpty()) {
                    chapterName.add("Vol.$it")
                }
            }

            attr.chapter?.let {
                if (it.isNotEmpty()) {
                    chapterName.add("Ch.$it")
                }
            }

            attr.title?.let {
                if (it.isNotEmpty()) {
                    if (chapterName.isNotEmpty()) {
                        chapterName.add("-")
                    }
                    chapterName.add(it)
                }
            }

            if (attr.externalUrl != null && attr.data.isEmpty()) {
                return null
            }

            // if volume, chapter and title is empty its a oneshot
            if (chapterName.isEmpty()) {
                chapterName.add("Oneshot")
            }

            // In future calculate [END] if non mvp api doesnt provide it

            return SChapter.create().apply {
                url = "/chapter/${chapterDataDto.id}"
                name = cleanString(chapterName.joinToString(" "))
                date_upload = parseDate(attr.publishAt)
                scanlator = groups
            }
        } catch (e: Exception) {
            Log.e("MangaDex", "error parsing chapter", e)
            throw(e)
        }
    }
}
