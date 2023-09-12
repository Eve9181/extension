package eu.kanade.tachiyomi.animeextension.tr.animeler

import eu.kanade.tachiyomi.animeextension.tr.animeler.dto.TaxonomyDto
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimelerFilters {
    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    open class CheckBoxFilterList(name: String, val pairs: Array<Pair<String, Int>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>(name, pairs.map { CheckBoxVal(it.first, false) })

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    private inline fun <reified R> AnimeFilterList.getFirst(): R = first { it is R } as R

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (getFirst<R>() as QueryPartFilter).toQueryPart()
    }

    private inline fun <reified R> AnimeFilterList.parseCheckbox(
        options: Array<Pair<String, Int>>,
        name: String,
    ): TaxonomyDto {
        return (getFirst<R>() as CheckBoxFilterList).state
            .filter { it.state }
            .map { checkbox -> options.find { it.first == checkbox.name }!!.second }
            .let { TaxonomyDto(name, it) }
    }

    class GenresFilter : CheckBoxFilterList("Genres", AnimelerFiltersData.GENRES)
    class StatusFilter : CheckBoxFilterList("Durumu", AnimelerFiltersData.STATUS)
    class ProducersFilter : CheckBoxFilterList("Yapımcı", AnimelerFiltersData.PRODUCERS)
    class StudiosFilter : CheckBoxFilterList("Stüdyo", AnimelerFiltersData.GENRES)
    class TypesFilter : CheckBoxFilterList("Tür", AnimelerFiltersData.TYPES)

    class OrderFilter : AnimeFilter.Sort(
        "Order by",
        AnimelerFiltersData.ORDERS.map { it.first }.toTypedArray(),
        Selection(0, false),
    )
    class YearFilter : QueryPartFilter("Yil", AnimelerFiltersData.YEARS)
    class SeasonFilter : QueryPartFilter("Sezon", AnimelerFiltersData.SEASONS)

    val FILTER_LIST get() = AnimeFilterList(
        OrderFilter(),
        YearFilter(),
        SeasonFilter(),
        AnimeFilter.Separator(),
        GenresFilter(),
        StatusFilter(),
        ProducersFilter(),
        StudiosFilter(),
        TypesFilter(),
    )

    data class FilterSearchParams(
        val genres: TaxonomyDto = TaxonomyDto(),
        val status: TaxonomyDto = TaxonomyDto(),
        val producers: TaxonomyDto = TaxonomyDto(),
        val studios: TaxonomyDto = TaxonomyDto(),
        val types: TaxonomyDto = TaxonomyDto(),
        val order: String = "desc",
        val orderBy: String = "total_kiranime_views",
        val year: String = "",
        val season: String = "",
    )

    internal fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        val (order, orderBy) = filters.getFirst<OrderFilter>().state?.let {
            val order = if (it.ascending) "asc" else "desc"
            val orderBy = AnimelerFiltersData.ORDERS[it.index].second
            Pair(order, orderBy)
        } ?: Pair("desc", "total_kiranime_views")

        return FilterSearchParams(
            filters.parseCheckbox<GenresFilter>(AnimelerFiltersData.GENRES, "genre"),
            filters.parseCheckbox<StatusFilter>(AnimelerFiltersData.STATUS, "status"),
            filters.parseCheckbox<ProducersFilter>(AnimelerFiltersData.PRODUCERS, "producer"),
            filters.parseCheckbox<StudiosFilter>(AnimelerFiltersData.STUDIOS, "studio"),
            filters.parseCheckbox<TypesFilter>(AnimelerFiltersData.TYPES, "type"),
            order,
            orderBy,
            filters.asQueryPart<YearFilter>(),
            filters.asQueryPart<SeasonFilter>(),
        )
    }

    private object AnimelerFiltersData {
        val EVERY = Pair("Seçiniz", "")

        val GENRES = arrayOf(
            Pair("Action", 10),
            Pair("Adult Cast", 459),
            Pair("Adventure", 34),
            Pair("Aksiyon", 158),
            Pair("Antropomorfik", 220),
            Pair("Arabalar", 192),
            Pair("Aşk Üçgeni", 219),
            Pair("Askeri", 184),
            Pair("Avangart", 211),
            Pair("Bilim Kurgu", 171),
            Pair("Büyü", 159),
            Pair("CGDCT", 215),
            Pair("Childcare", 364),
            Pair("Çocuk Bakımı", 216),
            Pair("Çocuklar", 206),
            Pair("Comedy", 95),
            Pair("Comic", 228),
            Pair("Dedektif", 221),
            Pair("Delinquents", 405),
            Pair("Doğaüstü Güçler", 176),
            Pair("Dövüş Sanatları", 187),
            Pair("Dram", 180),
            Pair("Drama", 51),
            Pair("Ecchi", 22),
            Pair("Fantastik", 160),
            Pair("Fantasy", 13),
            Pair("Gag Humor", 397),
            Pair("Gerilim", 172),
            Pair("Girls Love", 65),
            Pair("Gizem", 173),
            Pair("Gore", 358),
            Pair("Gourmet", 473),
            Pair("Harem", 170),
            Pair("Historical", 359),
            Pair("Horror", 119),
            Pair("İdol", 225),
            Pair("Idols (Female)", 292),
            Pair("Isekai", 196),
            Pair("Iyashikei", 223),
            Pair("Josei", 178),
            Pair("Komedi", 168),
            Pair("Korku", 174),
            Pair("Kumar Oyunu", 222),
            Pair("Macera", 161),
            Pair("Mahou Shoujo", 214),
            Pair("Martial Arts", 425),
            Pair("Mecha", 193),
            Pair("Medikal", 254),
            Pair("Military", 394),
            Pair("Mitoloji", 213),
            Pair("Music", 522),
            Pair("Müzik", 203),
            Pair("Mystery", 76),
            Pair("Mythology", 316),
            Pair("Okul", 179),
            Pair("OP M.C.", 541),
            Pair("Oyun", 191),
            Pair("Parodi", 197),
            Pair("Polisiye", 186),
            Pair("Psikolojik", 175),
            Pair("Psychological", 303),
            Pair("Rebirth", 517),
            Pair("Reenkarnasyon", 217),
            Pair("Reincarnation", 381),
            Pair("Revenge", 518),
            Pair("Romance", 29),
            Pair("Romantic Subtext", 270),
            Pair("Romantizm", 181),
            Pair("Sahne Sanatçıları", 227),
            Pair("Samuray", 188),
            Pair("School", 289),
            Pair("Sci-Fi", 45),
            Pair("Seinen", 183),
            Pair("Şeytan", 189),
            Pair("Shoujo", 194),
            Pair("Shoujo Ai", 212),
            Pair("Shounen", 162),
            Pair("Shounen Ai", 210),
            Pair("Slice of Life", 128),
            Pair("Spor", 207),
            Pair("Sports", 144),
            Pair("Strategy Game", 434),
            Pair("Strateji Oyunu", 218),
            Pair("Süper Güçler", 177),
            Pair("Super Power", 362),
            Pair("Supernatural", 49),
            Pair("Survival", 415),
            Pair("Suspense", 78),
            Pair("Tarihi", 185),
            Pair("Team Sports", 369),
            Pair("Time Travel", 407),
            Pair("Uzay", 190),
            Pair("Vampir", 182),
            Pair("Video Game", 402),
            Pair("Visual Arts", 503),
            Pair("Workplace", 462),
            Pair("Yaşamdan Kesitler", 169),
            Pair("Yemek", 204),
            Pair("Yetişkin Karakterler", 226),
            Pair("Zaman Yolculuğu", 224),
        )

        val STATUS = arrayOf(
            Pair("Airing", 3),
            Pair("Completed", 4),
            Pair("Not Yet Aired", 244),
            Pair("Upcoming", 2),
            Pair("Upcomming", 205),
        )

        val PRODUCERS = arrayOf(
            Pair("A-Sketch", 137),
            Pair("ABC Animation", 60),
            Pair("ADK Emotions", 299),
            Pair("ADK Marketing Solutions", 106),
            Pair("Ai Addiction", 79),
            Pair("Aiming", 384),
            Pair("Akita Shoten", 373),
            Pair("Amusement Media Academy", 130),
            Pair("Animation Do", 340),
            Pair("Animax", 491),
            Pair("Aniplex", 30),
            Pair("APDREAM", 109),
            Pair("AQUAPLUS", 236),
            Pair("arma bianca", 80),
            Pair("ASCII Media Works", 529),
            Pair("Ashi Productions", 338),
            Pair("Asmik Ace", 347),
            Pair("AT-X", 81),
            Pair("Atelier Musa", 314),
            Pair("Avex Entertainment", 327),
            Pair("Avex Pictures", 266),
            Pair("B.CMAY PICTURES", 267),
            Pair("Bandai", 334),
            Pair("Bandai Namco Arts", 74),
            Pair("Bandai Namco Entertainment", 336),
            Pair("Bandai Namco Filmworks", 231),
            Pair("Bandai Namco Music Live", 232),
            Pair("Bandai Spirits", 11),
            Pair("Bandai Visual", 337),
            Pair("BeDream", 284),
            Pair("Being", 305),
            Pair("Bergamo", 110),
            Pair("Beyond C.", 392),
            Pair("Bibury Animation CG", 489),
            Pair("bilibili", 151),
            Pair("Bit grooove promotion", 58),
            Pair("BS Asahi", 263),
            Pair("BS Fuji", 12),
            Pair("BS NTV", 67),
            Pair("BS11", 107),
            Pair("Bushiroad", 241),
            Pair("Bushiroad Creative", 276),
            Pair("Bushiroad Move", 277),
            Pair("C-one", 131),
            Pair("CG Year", 282),
            Pair("China Literature Limited", 199),
            Pair("Chiptune", 127),
            Pair("CHOCOLATE", 482),
            Pair("Chosen", 429),
            Pair("Chugai Mining", 450),
            Pair("Cloud Art", 239),
            Pair("Cloud22", 248),
            Pair("Contents Seed", 68),
            Pair("Crest", 510),
            Pair("Crunchyroll", 141),
            Pair("CTW", 348),
            Pair("Culture Entertainment", 300),
            Pair("CyberAgent", 295),
            Pair("Cygames", 514),
            Pair("DAX Production", 163),
            Pair("Days", 269),
            Pair("Delfi sound", 557),
            Pair("DeNA", 291),
            Pair("Dentsu", 91),
            Pair("Disney Platform Distribution", 485),
            Pair("DMM Music", 96),
            Pair("DMM pictures", 97),
            Pair("DMM.com", 438),
            Pair("Docomo Anime Store", 21),
            Pair("Dream Shift", 111),
            Pair("dugout", 124),
            Pair("Egg Firm", 35),
            Pair("Energy Studio", 273),
            Pair("Enterbrain", 343),
            Pair("Epicross", 297),
            Pair("Exa International", 344),
            Pair("F.M.F", 513),
            Pair("flying DOG", 83),
            Pair("Foch Films", 436),
            Pair("Frontier Works", 82),
            Pair("Fuji Creative", 401),
            Pair("Fuji TV", 14),
            Pair("Fujimi Shobo", 315),
            Pair("FuRyu", 36),
            Pair("Futabasha", 349),
            Pair("FUTURE LEAP", 350),
            Pair("Geek Pictures", 472),
            Pair("Genco", 84),
            Pair("Geneon Universal Entertainment", 117),
            Pair("Gentosha Comics", 376),
            Pair("Glovision", 325),
            Pair("Good Smile Company", 233),
            Pair("Good Smile Film", 458),
            Pair("GREE", 37),
            Pair("GREE Entertainment", 377),
            Pair("Grooove", 342),
            Pair("Hakuhodo", 367),
            Pair("Hakuhodo DY Media Partners", 15),
            Pair("Hakuhodo DY Music &amp; Pictures", 38),
            Pair("Hakusensha", 320),
            Pair("Half H.P Studio", 25),
            Pair("Half HP Studio", 508),
            Pair("Happinet Phantom Studios", 246),
            Pair("Heart Company", 474),
            Pair("High Energy Studio", 447),
            Pair("HM Heros", 283),
            Pair("Hobby Japan", 464),
            Pair("HoriPro International", 307),
            Pair("Ichijinsha", 132),
            Pair("INCS toenter", 365),
            Pair("Infinite", 257),
            Pair("INSPION Edge", 408),
            Pair("iQIYI", 274),
            Pair("IRMA LA DOUCE", 148),
            Pair("Jinnan Studio", 102),
            Pair("JR East Marketing &amp; Communications", 378),
            Pair("Jumondo", 260),
            Pair("K contents", 383),
            Pair("Kadokawa", 26),
            Pair("Kadokawa Media House", 27),
            Pair("Kadokawa Shoten", 332),
            Pair("Kanetsu Investment", 133),
            Pair("Kansai Telecasting", 253),
            Pair("KDDI", 393),
            Pair("King Records", 237),
            Pair("Kizuna AI", 520),
            Pair("KLab", 234),
            Pair("KlockWorx", 40),
            Pair("Kodansha", 98),
            Pair("Konami", 339),
            Pair("Konami Cross Media NY", 433),
            Pair("Konami Digital Entertainment", 301),
            Pair("Kuaikan Manhua", 479),
            Pair("Kyoraku Industrial Holdings", 157),
            Pair("Lantis", 52),
            Pair("Lawson", 333),
            Pair("Lawson HMV Entertainment", 138),
            Pair("Legs", 521),
            Pair("LHL Culture", 456),
            Pair("MAGES.", 238),
            Pair("Magic Bus", 487),
            Pair("Magic Capsule", 88),
            Pair("MAGNET", 309),
            Pair("Mainichi Broadcasting System", 75),
            Pair("Marui Group", 360),
            Pair("Marvelous", 69),
            Pair("Marvelous AQL", 311),
            Pair("MediaNet", 85),
            Pair("MediBang", 398),
            Pair("Medicos Entertainment", 108),
            Pair("Medo", 574),
            Pair("Micro House", 379),
            Pair("Micro Magazine Publishing", 380),
            Pair("Mixer", 507),
            Pair("Movic", 16),
            Pair("Muse Communication", 142),
            Pair("My Theater D.D.", 261),
            Pair("Nagoya Broadcasting Network", 61),
            Pair("NBCUniversal Entertainment Japan", 70),
            Pair("NetEase", 388),
            Pair("Netflix", 403),
            Pair("NewGin", 385),
            Pair("NHK", 356),
            Pair("NHK Enterprises", 357),
            Pair("NichiNare", 439),
            Pair("Nichion", 440),
            Pair("Nihon Ad Systems", 103),
            Pair("Nikkatsu", 368),
            Pair("Nippon Animation", 494),
            Pair("Nippon Columbia", 28),
            Pair("Nippon Television Music", 488),
            Pair("Nippon Television Network", 255),
            Pair("Nitroplus", 154),
            Pair("NTT Plala", 310),
            Pair("Overlap", 278),
            Pair("Paper Plane Animation Studio", 443),
            Pair("Pia", 419),
            Pair("Pierrot", 399),
            Pair("Pony Canyon", 59),
            Pair("Pony Canyon Enterprise", 413),
            Pair("PRA", 317),
            Pair("Precious tone", 553),
            Pair("Production Ace", 293),
            Pair("Production I.G", 414),
            Pair("Pure Arts", 426),
            Pair("Q-Tec", 104),
            Pair("Quaras", 477),
            Pair("Rakuonsha", 140),
            Pair("Ranzai Studio", 428),
            Pair("Rialto Entertainment", 149),
            Pair("Saber Links", 461),
            Pair("Sammy", 72),
            Pair("SB Creative", 41),
            Pair("Seikaisha", 155),
            Pair("Shochiku", 328),
            Pair("Shogakukan", 54),
            Pair("Shogakukan Music &amp; Digital Entertainment", 478),
            Pair("Shogakukan-Shueisha Productions", 153),
            Pair("Shounen Gahousha", 123),
            Pair("Showgate", 318),
            Pair("Shueisha", 32),
            Pair("Shufunotomo", 453),
            Pair("Sonilude", 135),
            Pair("Sony Music Entertainment", 92),
            Pair("Sony Music Solutions", 471),
            Pair("Sony Pictures Entertainment", 476),
            Pair("Sound Team Don Juan", 500),
            Pair("Square Enix", 46),
            Pair("Starchild Records", 323),
            Pair("Starry Cube", 352),
            Pair("Straight Edge", 47),
            Pair("Stray Cats", 302),
            Pair("Studio Easter", 290),
            Pair("Studio Hibari", 331),
            Pair("Studio Mausu", 48),
            Pair("Sumzap", 126),
            Pair("Sun TV", 346),
            Pair("TBS", 272),
            Pair("TC Entertainment", 312),
            Pair("Tencent", 445),
            Pair("Tencent Animation &amp; Comics", 209),
            Pair("Tencent Games", 326),
            Pair("Tencent Penguin Pictures", 201),
            Pair("TMS Entertainment", 389),
            Pair("TO Books", 468),
            Pair("Toei animation", 166),
            Pair("Toei Video", 113),
            Pair("Tohan Corporation", 361),
            Pair("Tohjak", 490),
            Pair("TOHO animation", 17),
            Pair("Tohokushinsha Film Corporation", 143),
            Pair("Tokyo MX", 63),
            Pair("Toy's Factory", 370),
            Pair("Trinity Sound", 329),
            Pair("TV Aichi", 294),
            Pair("TV Asahi", 371),
            Pair("TV Tokyo", 164),
            Pair("TV Tokyo Music", 324),
            Pair("TVA advance", 470),
            Pair("Twin Engine", 281),
            Pair("Ultra Super Pictures", 20),
            Pair("Universal Music Japan", 353),
            Pair("VAP", 256),
            Pair("Visual Arts", 64),
            Pair("Vobile Japan", 391),
            Pair("Wanda Media", 288),
            Pair("Warner Bros. Japan", 43),
            Pair("WOWMAX", 504),
            Pair("WOWOW", 87),
            Pair("Xuanshi Tangmen", 448),
            Pair("Yahoo! Japan", 396),
            Pair("Yokohama Animation Lab", 441),
            Pair("Yomiko Advertising", 330),
            Pair("Yomiuri Advertising", 73),
            Pair("Yomiuri Shimbun", 114),
            Pair("Yomiuri Telecasting", 363),
            Pair("Yomiuri TV Enterprise", 57),
            Pair("Yostar", 558),
            Pair("Youku", 268),
            Pair("Yuewen Animation &amp; Comics", 467),
        )

        val STUDIOS = arrayOf(
            Pair("2:10 AM Animation", 430),
            Pair("8bit", 44),
            Pair("A-1 Pictures", 116),
            Pair("A.C.G.T.", 265),
            Pair("Actas", 568),
            Pair("Ajia-do", 526),
            Pair("Arvo animation", 145),
            Pair("Asahi Production", 509),
            Pair("Ashi Productions", 465),
            Pair("AtelierPontdarc", 247),
            Pair("Axsiz", 567),
            Pair("B.CMAY PICTURES", 480),
            Pair("Bakken Record", 481),
            Pair("Bandai Namco Pictures", 355),
            Pair("Bibury Animation Studios", 387),
            Pair("Big firebird culture", 93),
            Pair("bilibili", 374),
            Pair("Blade", 279),
            Pair("Bones", 90),
            Pair("Brain's Base", 484),
            Pair("Bug films", 569),
            Pair("BYMENT", 435),
            Pair("C-Station", 502),
            Pair("C2C", 375),
            Pair("CG Year", 411),
            Pair("China south angel", 571),
            Pair("Chongzhuo Animation", 412),
            Pair("Chosen", 285),
            Pair("Clap", 540),
            Pair("Cloud Hearts", 437),
            Pair("CloverWorks", 264),
            Pair("Connect", 451),
            Pair("CygamesPictures", 519),
            Pair("Da huoniao donghua", 94),
            Pair("Dancing CG Studio", 304),
            Pair("David Production", 152),
            Pair("DC Impression Vision", 466),
            Pair("Diomedéa", 252),
            Pair("Djinn Power", 527),
            Pair("DLE", 382),
            Pair("Doga Kobo", 115),
            Pair("Drive", 296),
            Pair("EKACHI EPILKA", 195),
            Pair("Elite Animation", 444),
            Pair("EMT Squared", 306),
            Pair("Encourage Films", 147),
            Pair("ENGI", 156),
            Pair("feel.", 250),
            Pair("Foch", 536),
            Pair("Foch Films", 200),
            Pair("Gaina", 251),
            Pair("Gallop", 432),
            Pair("GARDEN", 416),
            Pair("Garden Culture", 275),
            Pair("Geek Toys", 24),
            Pair("Gekkou", 505),
            Pair("Geno Studio", 280),
            Pair("GIFTanimation", 242),
            Pair("Gohands", 561),
            Pair("Good smile company", 417),
            Pair("Graphinica", 118),
            Pair("Haoliners Animation League", 442),
            Pair("Heart &amp; soul animation", 455),
            Pair("Ilca", 556),
            Pair("J.C.Staff", 39),
            Pair("Kinema Citrus", 243),
            Pair("Kung Fu Frog Animation", 524),
            Pair("Kyoto Animation", 341),
            Pair("Lapin track", 562),
            Pair("Larx entertainment", 563),
            Pair("Lay-duce", 409),
            Pair("Lerche", 229),
            Pair("Liber", 460),
            Pair("LIDENFILMS", 139),
            Pair("Liyu culture", 532),
            Pair("Lx animation studio", 531),
            Pair("Madhouse", 120),
            Pair("Magic Bus", 308),
            Pair("Maho Film", 53),
            Pair("MAPPA", 125),
            Pair("Millepensee", 495),
            Pair("Motion Magic", 287),
            Pair("Movic", 418),
            Pair("NAZ", 262),
            Pair("New deer", 544),
            Pair("Nexus", 386),
            Pair("Nhk", 533),
            Pair("Nhk enterprises", 534),
            Pair("Nice Boat Animation", 496),
            Pair("Nippon animation", 535),
            Pair("Nomad", 134),
            Pair("OLM", 410),
            Pair("Olm team yoshioka", 564),
            Pair("Orange", 452),
            Pair("Oriental Creative Color", 528),
            Pair("Original force", 530),
            Pair("P.A. Works", 62),
            Pair("Passion paint animation", 576),
            Pair("Passione", 249),
            Pair("Pencil Lead Animate", 525),
            Pair("Pia", 420),
            Pair("Pie in the sky", 554),
            Pair("Pierrot", 71),
            Pair("Pierrot Plus", 313),
            Pair("Pine jam", 538),
            Pair("Polygon Pictures", 475),
            Pair("Pony canyon", 421),
            Pair("Production I.G", 77),
            Pair("Project No.9", 112),
            Pair("Qingxiang Culture", 427),
            Pair("Qiyuan Yinghua", 457),
            Pair("Quad", 483),
            Pair("Quyue Technology", 493),
            Pair("Revoroot", 230),
            Pair("Rocen", 498),
            Pair("Ruo Hong Culture", 259),
            Pair("Satelight", 122),
            Pair("Sb creative", 422),
            Pair("Seven", 351),
            Pair("Seven Arcs", 286),
            Pair("Shaft", 235),
            Pair("Shenman entertainment", 572),
            Pair("Shin-Ei Animation", 486),
            Pair("Shirogumi", 552),
            Pair("Signal.MD", 523),
            Pair("Silver", 146),
            Pair("SILVER LINK.", 271),
            Pair("Soyep", 202),
            Pair("Sparkly Key Animation Studio", 208),
            Pair("Staple Entertainment", 240),
            Pair("Studio 3Hz", 258),
            Pair("Studio 4°C", 395),
            Pair("Studio A-CAT", 366),
            Pair("Studio bind", 570),
            Pair("Studio Blanc.", 449),
            Pair("Studio Deen", 86),
            Pair("Studio Elle", 511),
            Pair("Studio Flad", 400),
            Pair("Studio ghibli", 560),
            Pair("Studio gokumi", 566),
            Pair("Studio Jemi", 245),
            Pair("Studio Kafka", 501),
            Pair("Studio Kai", 129),
            Pair("Studio LAN", 298),
            Pair("Studio Lings", 499),
            Pair("Studio Mir", 404),
            Pair("studio MOTHER", 345),
            Pair("Studio Palette", 497),
            Pair("Studio Signpost", 492),
            Pair("Sunrise", 55),
            Pair("Sunrise beyond", 555),
            Pair("SynergySP", 506),
            Pair("Telecom animation film", 150),
            Pair("Tencent Penguin Pictures", 454),
            Pair("Tezuka Productions", 66),
            Pair("TMS Entertainment", 99),
            Pair("TNK", 319),
            Pair("Toei Animation", 101),
            Pair("Toho", 423),
            Pair("Tokyo mx", 424),
            Pair("Trigger", 18),
            Pair("TROYCA", 390),
            Pair("Typhoon Graphics", 512),
            Pair("ufotable", 33),
            Pair("Wawayu Animation", 515),
            Pair("White Fox", 105),
            Pair("Wit Studio", 136),
            Pair("Wolfsbane", 354),
            Pair("Wonder Cat Animation", 446),
            Pair("Xuni Ying Ye", 516),
            Pair("Yokohama Animation Lab", 372),
            Pair("Yostar pictures", 559),
            Pair("Youku", 573),
            Pair("Yumeta Company", 167),
            Pair("Zero-G", 463),
            Pair("Zexcs", 537),
        )

        val TYPES = arrayOf(
            Pair("Movie", 165),
            Pair("ONA", 89),
            Pair("OVA", 121),
            Pair("Special", 198),
            Pair("TV", 19),
        )

        val ORDERS = arrayOf(
            Pair("Popüler", "total_kiranime_views"),
            Pair("Favori", "bookmark_count"),
            Pair("Başlık", "title"),
            Pair("Yayımlandı", "date"),
            Pair("Güncellendi", "kiranime_anime_updated"),
        )

        val YEARS = arrayOf(EVERY) + (2024 downTo 1990).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()

        val SEASONS = arrayOf(
            EVERY,
            Pair("Kış", "winter"),
            Pair("Spring", "spring"),
            Pair("Summer", "summer"),
            Pair("Sonbahar", "fall"),
        )
    }
}
