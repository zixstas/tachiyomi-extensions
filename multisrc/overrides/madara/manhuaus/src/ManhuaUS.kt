package eu.kanade.tachiyomi.extension.en.manhuaus

import eu.kanade.tachiyomi.multisrc.madara.Madara

class ManhuaUS : Madara("ManhuaUS", "https://manhuaus.com", "en") {
    override val useNewChapterEndpoint: Boolean = true

    override val pageListParseSelector = "div.page-break, li.blocks-gallery-item, .reading-content .text-left:not(:has(.blocks-gallery-item)) img"
}
