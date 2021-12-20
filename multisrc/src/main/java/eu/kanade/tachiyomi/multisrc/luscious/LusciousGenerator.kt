package eu.kanade.tachiyomi.multisrc.luscious

import generator.ThemeSourceData.MultiLang
import generator.ThemeSourceGenerator

class LusciousGenerator : ThemeSourceGenerator {

    override val themePkg = "luscious"

    override val themeClass = "Luscious"

    override val baseVersionCode: Int = 15

    override val sources = listOf(
        MultiLang("Luscious", "https://www.luscious.net", listOf("en", "ja", "es", "it", "de", "fr", "zh", "ko", "other", "pt-BR", "th", "all"), isNsfw = true, className = "LusciousFactory", overrideVersionCode = 2),
        MultiLang("Luscious (Members)", "https://members.luscious.net", listOf("en", "ja", "es", "it", "de", "fr", "zh", "ko", "other", "pt-BR", "th", "all"), isNsfw = true, className = "LusciousMembersFactory", pkgName = "lusciousmembers"), // Requires Account
        MultiLang("Luscious (API)", "https://api.luscious.net", listOf("en", "ja", "es", "it", "de", "fr", "zh", "ko", "other", "pt-BR", "th", "all"), isNsfw = true, className = "LusciousAPIFactory", pkgName = "lusciousapi")
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            LusciousGenerator().createAll()
        }
    }
}
