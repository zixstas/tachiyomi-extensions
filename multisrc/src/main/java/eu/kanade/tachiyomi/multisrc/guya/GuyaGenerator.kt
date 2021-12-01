package eu.kanade.tachiyomi.multisrc.guya

import generator.ThemeSourceData.MultiLang
import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

class GuyaGenerator : ThemeSourceGenerator {

    override val themePkg = "guya"

    override val themeClass = "Guya"

    override val baseVersionCode = 3

    override val sources = listOf(
        SingleLang("Guya", "https://guya.moe", "en", overrideVersionCode = 18),
        SingleLang("Danke fürs Lesen", "https://danke.moe", "en", className = "DankeFursLesen"),
        SingleLang("Hachirumi", "https://hachirumi.com", "en", isNsfw = true),
        MultiLang("Magical Translators", "https://mahoushoujobu.com", listOf("en", "pl")),
    )
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            GuyaGenerator().createAll()
        }
    }
}
