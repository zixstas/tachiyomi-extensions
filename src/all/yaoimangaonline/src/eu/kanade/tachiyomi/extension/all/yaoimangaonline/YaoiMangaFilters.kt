package eu.kanade.tachiyomi.extension.all.yaoimangaonline

import eu.kanade.tachiyomi.source.model.Filter

class CategoryFilter(
    values: Array<String> = categories.keys.toTypedArray()
) : Filter.Select<String>("Category", values) {
    override fun toString() = categories[values[state]]!!

    companion object {
        private val categories = mapOf(
            "ALL" to "-1",
            "07-Ghost dj" to "121",
            "A3! Dj" to "5315",
            "Ace Attorney Dj" to "5899",
            "Ai no Kusabi DJ" to "87",
            "AI: The Somnium Files Dj" to "6774",
            "Aldnoah.Zero Dj" to "4978",
            "All Out!! Dj" to "3414",
            "Ameiro Paradox Dj" to "1741",
            "Ansatsu Kyoushitsu Dj" to "3437",
            "Antique Bakery dj" to "123",
            "Ao no Exorcist Dj" to "639",
            "Arashi Dj" to "2518",
            "Arslan Senki Dj" to "5098",
            "Assassination Classroom Dj" to "2522",
            "Assassin’s Creed Dj" to "2519",
            "Astro Fighter Sunred Dj" to "348",
            "Attack on Titan dj" to "108",
            "Avengers Dj" to "2532",
            "Azazel san! dj" to "214",
            "Baccano Dj" to "5925",
            "Bakumatsu Rock Dj" to "2552",
            "Banana Fish Dj" to "5926",
            "Band Yarouze! Dj" to "3941",
            "Bara" to "14",
            "Barakamon dj" to "132",
            "Basquash! Dj" to "2550",
            "Batman Dj" to "7861",
            "Battle Spirits Dj" to "2555",
            "Beelzebub dj" to "125",
            "Berserk Dj" to "2558",
            "Beyblade Burst Dj" to "3512",
            "Big Hero 6 Dj" to "3464",
            "Black Clover Dj" to "5924",
            "Black Jack Dj" to "4006",
            "Blazblue Dj" to "351",
            "Blood Blockade Battlefront Dj" to "3987",
            "Boktai Dj" to "1307",
            "Boku Dake ga Inai Machi Dj" to "3529",
            "Boku no Hero Academia Dj" to "3362",
            "Boondock Saints Dj" to "2620",
            "Border Dj" to "2617",
            "Brave Story Dj" to "2622",
            "Buddy Complex Dj" to "2625",
            "Bungou Stray Dogs Dj" to "5897",
            "Bungou to Alchemist Dj" to "6365",
            "Call of Duty Modern Warfare DJ" to "2794",
            "Candidate for Goddess Dj" to "2627",
            "Cardfight!! Vanguard Dj" to "3611",
            "Casshern Sins Dj" to "2628",
            "Castlevania Dj" to "2631",
            "Chinko no Tsubuyaki Dj" to "2865",
            "Chobits Dj" to "2546",
            "Chouhatsu Denkou Sekka Boys Dj" to "4977",
            "Chousoku Henkei Gyrozetter Dj" to "2633",
            "Clannad Dj" to "2635",
            "Cluster Edge Dj" to "2640",
            "Code Geass Dj" to "2644",
            "ConCon-Collector Dj" to "478",
            "Crows ZERO Dj" to "3331",
            "D.Gray-man Dj" to "2255",
            "Daiya no Ace Dj" to "2676",
            "Danganronpa Dj" to "3712",
            "Dark Avengers Dj" to "2529",
            "Darkstalkers/ Red Earth dj" to "383",
            "Datenshi ni Sasageru Uta dj" to "949",
            "DAYS Dj" to "3514",
            "Dead by Daylight Dj" to "4970",
            "Detective Conan Dj" to "3333",
            "Devil May Cry 5 Dj" to "5915",
            "Devilman Dj" to "4975",
            "Donten ni Warau Dj" to "3922",
            "Doushitemo Furetakunai Dj" to "3052",
            "Dr. Stone Dj" to "7324",
            "Dragalia Lost Dj" to "5914",
            "Dragon Ball Dj" to "2799",
            "Dragon Quest Dj" to "398",
            "Dragon’s Dogma Dj" to "3679",
            "DRAMAtical Murder Dj" to "3486",
            "Drifters Dj" to "5930",
            "Durarara dj" to "82",
            "Dynasty Warriors Dj" to "3218",
            "Enen no Shouboutai Dj" to "5933",
            "Ensemble Stars! Dj" to "1868",
            "Enzai Dj" to "571",
            "Eternal Arcadia Dj" to "2825",
            "Eyeshield 21 Dj" to "3644",
            "Fantastic Boyfriends Dj" to "1704",
            "Fate/Grand Order Dj" to "3117",
            "Fate/Stay Night Dj" to "3656",
            "Fate/Zero Dj" to "3235",
            "Final Fantasy Dj" to "3277",
            "Fire Emblem Dj" to "692",
            "Free! dj" to "71",
            "Friday the 13th Dj" to "3944",
            "Fullmetal Alchemist Dj" to "409",
            "Furry" to "15",
            "Future Card Buddyfight Dj" to "387",
            // "Gay Movies" to "3017",
            "Gay Novels" to "1852",
            "Gekkan Shoujo Nozaki-kun Dj" to "3940",
            "Genshin Impact Dj" to "6423",
            "GetBackers Dj" to "5278",
            "Giant Killing Dj" to "3216",
            "Gingitsune Dj" to "325",
            "Gintama Dj" to "3225",
            "Golden Kamuy Dj" to "5901",
            "Granblue Fantasy Dj" to "3758",
            "Gravitation Dj" to "2069",
            "Gravity Falls Dj" to "3855",
            "Guardians of the Galaxy Dj" to "449",
            "Gugure! Kokkuri-san Dj" to "3877",
            "Gundam Dj" to "5931",
            "Gundam Wing Dj" to "5900",
            "Gyakuten Saiban Dj" to "2834",
            "Hacka Doll Dj" to "3472",
            "Haikyuu!! Dj" to "512",
            "Hakkenden Dj" to "2498",
            "Hakuouki Dj" to "2495",
            "Harry Potter Dj" to "2489",
            "Hataraku Saibou Dj" to "4191",
            "Heat Guy J Dj" to "2482",
            "Heroman Dj" to "2479",
            "Hetalia Dj" to "191",
            "Hidoku Shinaide Dj" to "754",
            "Hikaru No Go Dj" to "2476",
            "Hinomaru Zumou Dj" to "2473",
            "Honeycomb Child Dj" to "306",
            "Hoozuki no Reitetsu Dj" to "3810",
            "How to Train Your Dragon Dj" to "196",
            "Hunter x Hunter Dj" to "3562",
            "Hypnosis Mic Dj" to "4289",
            "Identity V Dj" to "5929",
            "IDOLiSH7 Dj" to "3773",
            "Inazuma Eleven Dj" to "2279",
            "Initial D Dj" to "2464",
            "Interval Dj" to "2462",
            "InuYasha Dj" to "2455",
            "Ixion Saga Dj" to "2454",
            "Jinrou Judgement Dj" to "7862",
            "Jojo DJ" to "1280",
            "Jojo no Kimyou na Bouken Dj" to "3193",
            "Jormungand Dj" to "6228",
            "Jujutsu Kaisen Dj" to "5895",
            "K Project Dj" to "1014",
            "Kagerou Project Dj" to "4094",
            "Kaiji Dj" to "2828",
            "Kakumeiki Valvrave Dj" to "2450",
            "Kami-sama no Ude no Naka de Dj" to "3056",
            "Kanpai! Dj" to "2445",
            "Kantai Collection Dj" to "3664",
            "Karamete de Kudoite Dj" to "1933",
            "Katekyo Hitman Reborn! Dj" to "2247",
            "Katekyo! Dj" to "1843",
            "Kekkai Sensen Dj" to "3695",
            "Kichiku Megane Dj" to "3816",
            "Kidou Senshi Gundam – Tekketsu no Orphans Dj" to "3129",
            "Kill la Kill Dj" to "2447",
            "Killing Stalking Dj" to "6593",
            "Kimetsu no Yaiba Dj" to "5898",
            "Kimi no Na wa dj" to "5423",
            "Kimi to boku Dj" to "2442",
            "King of Fighters Dj" to "6255",
            "King of Prism by Pretty Rhythm Dj" to "4973",
            "King’s Raid Dj" to "4969",
            "Kingdom Hearts Dj" to "2269",
            "Kiseijuu Dj" to "2996",
            "Knight’s & Magic Dj" to "3736",
            "Kocchi Muite Waratte Dj" to "3238",
            "Koibito Kijunchi Dj" to "882",
            "Koisuru Boukun Dj" to "1004",
            "Konjiki no Gash!! Dj" to "3892",
            "Kono Yoru no Subete Dj" to "2440",
            "Koshotengai no Hashihime Dj" to "4968",
            "Kuroko no Basuke Dj" to "1094",
            "Kuroshitsuji Dj" to "2252",
            "Kyoukai no Kanata Dj" to "2434",
            "Kyoushou Sentai Danjijaa Dj" to "3303",
            "Lamento dj" to "150",
            "Laputa Castle in the Sky Dj" to "2432",
            "League of Legends Dj" to "455",
            "Legendz: Tale of the Dragon Kings Dj" to "343",
            "Lord of the Rings Dj" to "148",
            "Loveless Dj" to "5928",
            "Lucky Dog Dj" to "3894",
            "Magi dj" to "139",
            "Maiden Rose DJ" to "724",
            "Megido72 Dj" to "4972",
            "Metal Gear Solid Dj" to "3395",
            "Mob Psycho 100 Dj" to "3450",
            "Mobile Fighter G Gundam Dj" to "2831",
            "Mobile Suit Gundam 00 Dj" to "3627",
            "Mobile Suit Gundam Tekketsu no Orphans Dj" to "702",
            "Morenatsu Dj" to "355",
            "Mousou Elektel Dj" to "994",
            "Mugen no Juunin Dj" to "2821",
            "Mushishi Dj" to "2242",
            "MUV-LUV Dj" to "2241",
            "My Hero Academia Dj" to "3137",
            "Naruto Dj" to "2402",
            "Natsume Yuujinchou Dj" to "3913",
            "Neon Genesis Evangelion Dj" to "2841",
            "New Danganronpa V3 Dj" to "3410",
            "NieR: Automata Dj" to "3722",
            "NightS Dj" to "4974",
            "Nightwing Dj" to "3170",
            "Ninku Dj" to "2926",
            "Noragami Dj" to "2237",
            "Octopath Traveler Dj" to "5531",
            "One Piece Dj" to "2384",
            "One Punch-Man Dj" to "3171",
            "Onmyou Taisenki Dj" to "496",
            "Ookiku Furikabutte Dj" to "5934",
            "Oreimo Dj" to "3260",
            "Osomatsu-san Dj" to "3120",
            "Ouran High School Host Club Dj" to "2919",
            "Owari no Seraph Dj" to "2909",
            "Palette Parade Dj" to "5923",
            "Pandora Hearts Dj" to "2896",
            "Persona 3 Dj" to "3551",
            "Persona 4 Dj" to "336",
            "Persona 5 Dj" to "3508",
            "Phantasy Star Dj" to "2235",
            "Phi Brain Dj" to "2233",
            "Phoenix Wright Dj" to "2804",
            "Phoenix Wright: Ace Attorney Dj" to "2835",
            "Pokemon dj" to "51",
            "Pretty Rhythm Dj" to "5950",
            "Prince of Tennis Dj" to "2336",
            "Professor Layton Dj" to "2332",
            "Promare Dj" to "4280",
            "Psycho Break Dj" to "1967",
            "Psycho Pass Dj" to "5910",
            "Pumpkin Scissors Dj" to "2231",
            "Punishing: Gray Raven Dj" to "5896",
            "Quiz Magic Academy Dj" to "5981",
            "Rakudai Ninja Rantarou Dj" to "1911",
            "Rampo Kitan: Game of Laplace Dj" to "3417",
            "Re: Zero kara Hajimeru Isekai Seikatsu Dj" to "5935",
            "Resident Evil Dj" to "2832",
            "Rival Schools Dj" to "2844",
            "Rokkuman Dj" to "2229",
            "Rurouni Kenshin Dj" to "2226",
            "Ryu ga Gotoku Dj" to "6335",
            "Sabita Yoru demo Koi wa Sasayaku Dj" to "1465",
            "Saiki Kusuo no Psi Nan Dj" to "3851",
            "Saint Seiya Dj" to "7860",
            "Saint Young Men Dj" to "2223",
            "Saiyuki Dj" to "5905",
            "Sakura Gari Dj" to "734",
            "Samurai Deeper Kyo Dj" to "2220",
            "Sekaiichi Hatsukoi Dj" to "2379",
            "Sengoku Basara Dj" to "3250",
            "Sherlock Dj" to "1710",
            "Shiki Dj" to "2323",
            "Shin SangokuMusou Dj" to "3217",
            "Shin Seiki Evangelion Dj" to "3156",
            "Shingeki no Kyojin dj" to "97",
            "Shining Wind Dj" to "461",
            "Shinkansen Henkei Robo Shinkalion Dj" to "5908",
            "Shinrabansou Choco Dj" to "2820",
            "Shironeko Project Dj" to "2321",
            "Shokugeki no Soma Dj" to "5907",
            "Shoshitsu Dj" to "2318",
            "Show by Rock!! Dj" to "3906",
            "ShuMao Dj" to "2218",
            "Silver Spoon Dj" to "4074",
            "SK8 the Infinity Dj" to "7178",
            "Slam Dunk Dj" to "6104",
            "SMAP! Dj" to "2987",
            "Smile PreCure! dj" to "370",
            "Soukyuu no Fafner Dj" to "2216",
            "SOUL CATCHER" to "5904",
            "Soul Eater Dj" to "2314",
            "Soul Hackers Dj" to "2313",
            "SoulCalibur Dj" to "2312",
            "South Park Dj" to "4030",
            "Splatoon Dj" to "2377",
            "SSSS.GRIDMAN Dj" to "5903",
            "Star Fox Dj" to "417",
            "Stardew Valley Dj" to "5932",
            "Starry Sky Dj" to "2211",
            "Strange Plus Dj" to "2212",
            "Street Fighter Dj" to "3769",
            "Strider Hiryuu Dj" to "2214",
            "Suisei no Gargantia Dj" to "3245",
            "Summer Wars Dj" to "2797",
            "Superman/Batman Dj" to "49",
            "Supernatural Dj" to "1705",
            "SWAT Kats Dj" to "3283",
            "Sword Art Online dj" to "185",
            "Tactics dj" to "182",
            "Taiiku Kyoushi Kiwame Dj" to "3806",
            "Tales of Destiny Dj" to "5902",
            "Tales of Graces dj" to "180",
            "Tales of the Abyss Dj" to "4073",
            "Tales of Vesperia Dj" to "3931",
            "Tales of Zestiria Dj" to "4976",
            "Tate no Yuusha no Nariagari Dj" to "4967",
            "The Evil Within Dj" to "1966",
            "THE IDOLM@STER Dj" to "3558",
            "The Legend Of Zelda Dj" to "4455",
            "The Lion King DJ" to "320",
            "The Melancholy of Haruhi Suzumiya Dj" to "3823",
            "The Mighty Thor Dj" to "2082",
            "The Outsiders Dj" to "576",
            "The Silence of the Lambs Dj" to "5982",
            "The Unlimited – Hyoubu Kyousuke Dj" to "834",
            "The World God Only Knows Dj" to "3667",
            "Tiger & Bunny dj" to "61",
            "To Aru Majutsu no Index Dj" to "2079",
            "Tobaku Haouden ZERO Dj" to "3927",
            "Togainu no Chi dj" to "46",
            "Tokkyuu!! Dj" to "2077",
            "Tokyo Ghoul Dj" to "1756",
            "Toriko Dj" to "2812",
            "Totally Captivated dj" to "178",
            "Touken Ranbu Dj" to "570",
            "Trigun Dj" to "174",
            "Turn A Gundam Dj" to "2480",
            "Twisted Wonderland Dj" to "5909",
            "Uchuu Kyoudai Dj" to "6052",
            "Under Grand Hotel Dj" to "3048",
            "Ura Brave Kingdom Dj" to "2074",
            "Urban Reign Dj" to "2846",
            "Usavich dj" to "47",
            "Uta no Prince-sama Dj" to "2259",
            "Utawarerumono Dj" to "230",
            "Valkariya Chronicles Dj" to "2806",
            "Valkyria Chronicles Dj" to "225",
            "Valvrave the Liberator Dj" to "2449",
            "Vampire Knight dj" to "171",
            "Vassalord dj" to "166",
            "Vinland Saga Dj" to "6424",
            "Vocaloid Dj" to "1719",
            "Voltron Dj" to "5414",
            "Warriors Orochi Dj" to "218",
            "Wild Adapter dj" to "164",
            "Winnie the Pooh Dj" to "3869",
            "World Trigger Dj" to "1091",
            "xxxHoLic Dj" to "1075",
            "Yahari Ore no Seishun Love Comedy wa Machigatteiru Dj" to "5916",
            "Yakuza DJ" to "161",
            "Yami no Matsuei Dj" to "6106",
            // "Yaoi Anime" to "2009",
            "Yaoi DJ" to "1",
            "Yaoi Gallery" to "198",
            "Yaoi Games Online" to "199",
            "Yaoi Magazines" to "3543",
            "Yaoi Manga" to "13",
            "Yaoi Oneshots" to "22",
            "Yarichin Bitch Club Dj" to "5927",
            "Yarou Fes 2013 Petit Dj" to "3433",
            "Yondemasuyo Azazel-san dj" to "210",
            "Young Black Jack dj" to "207",
            "Yowamushi Pedal Dj" to "2273",
            "Yu Yu Hakusho Dj" to "1087",
            "Yu-Gi-Oh! Dj" to "1027",
            "Yuri!!! on Ice Dj" to "2503",
            "Zettai Karen Children dj" to "50",
            "Zootopia Dj" to "2075",
        )
    }
}

class TagFilter(
    values: Array<String> = tags.keys.toTypedArray()
) : Filter.Select<String>("Tag", values) {
    override fun toString() = tags[values[state]]!!

    companion object {
        private val tags = mapOf(
            "ALL" to "",
            "Ahegao" to "ahegao",
            "Bara" to "bara",
            "BDSM Yaoi" to "bdsm",
            "Beastality" to "beastality",
            "Big Penis" to "big-penis",
            "Blowjob" to "blowjob",
            "Bondage" to "bondage",
            "Chinese" to "chinese",
            "Comedy" to "comedy",
            "Completed Yaoi Manga" to "completed-yaoi-manga",
            "Cross-dressing" to "cross-dressing",
            "Cute" to "cute",
            "Dark skin" to "dark-skin",
            "Drama" to "drama",
            "English" to "english",
            "Full Color" to "full-color",
            "Group Sex" to "group-sex",
            "Handjob" to "handjob",
            "Hardcore" to "hardcore",
            "Hard Yaoi" to "hard-yaoi",
            "Hentai" to "hentai",
            "Hentai Yaoi" to "hentai-yaoi",
            "Hentai Yaoi Manga" to "hentai-yaoi-manga",
            "Incest" to "incest",
            "Japanese" to "japanese",
            "Komik Yaoi Hentai" to "komik-yaoi-hentai",
            "Korean" to "korean",
            "Masturbation" to "masturbation",
            "Megane" to "megane",
            "Muscle" to "muscle",
            "Nipple play" to "nipple-play",
            "Rape" to "rape",
            "Romance" to "romance",
            "School Life" to "school-life",
            "Sex toy" to "sex-toy",
            "Shounen Ai" to "shounen-ai",
            "Slice of Life" to "slice-of-life",
            "Smut" to "smut",
            "Threesome" to "threesome",
            "Uncensored Yaoi" to "uncensored-yaoi",
            "Webtoon" to "webtoon",
            "Yaoi" to "yaoi",
            "Yaoi Hentai" to "yaoi-hentai",
            "Yaoi Hentai Manga" to "yaoi-hentai-manga",
            "Yaoi Sex" to "yaoi-sex",
        )
    }
}
