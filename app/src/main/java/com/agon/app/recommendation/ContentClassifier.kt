package com.agon.app.recommendation

import com.agon.app.data.model.StreamItem

/**
 * ContentClassifier — Smart Learning Channels Engine
 *
 * ════════════════════════════════════════════════════════════════════════
 *  PURPOSE
 * ════════════════════════════════════════════════════════════════════════
 *  Classifies every channel into one of four content types based on its
 *  name + group:
 *
 *    📚 LEARNING  — documentaries, science, history, nature, religious,
 *                   education, culture
 *    📰 NEWS      — news, breaking, economy, politics
 *    ⚽ SPORTS    — sports channels (detected via keyword matching;
 *                   the MatchHarvester handles live match detection
 *                   separately)
 *    🎬 ENTERTAINMENT — movies, series, music, everything else
 *
 *  The classification powers:
 *    1. Badge display on channel cards (📚/📰/⚽/🎬)
 *    2. "Learning Time" tracking in WatchHistoryManager
 *    3. Smart content-based filtering in the dashboard
 *    4. Learning recommendations ("try a documentary channel")
 *
 *  ALGORITHM:
 *    The classifier uses multi-language keyword matching (Arabic, English,
 *    French). It concatenates the channel name + group into a single
 *    lowercase string and checks for keyword presence. The matching is
 *    O(keywords × name_length) — fast enough for 22k channels.
 *
 *  CACHING:
 *    The classification result is cached per-URL in a ConcurrentHashMap
 *    so repeated calls (e.g. during scrolling) don't re-scan. The cache
 *    is cleared on [clearCache] (called by PlaylistRepository.clearSession).
 * ════════════════════════════════════════════════════════════════════════
 */
object ContentClassifier {

    // ════════════════════════════════════════════════════════════════════════
    //  CONTENT TYPES
    // ════════════════════════════════════════════════════════════════════════

    enum class ContentType(val label: String, val icon: String) {
        LEARNING("تعليمي", "📚"),
        NEWS("إخباري", "📰"),
        SPORTS("رياضي", "⚽"),
        ENTERTAINMENT("ترفيهي", "🎬")
    }

    // ════════════════════════════════════════════════════════════════════════
    //  KEYWORD DICTIONARIES (multi-language)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Learning content keywords — documentaries, science, history, nature,
     * religious, education, culture. Matched case-insensitively against
     * the channel name + group.
     */
    private val LEARNING_KEYWORDS = listOf(
        // Arabic
        "وثائقي", "علم", "تاريخ", "طبيعة", "ديني", "قرآن", "قران", "تعليم",
        "مدرسة", "جامعة", "ثقافة", "معرفة", "اكتشاف", "حضارة", "اسلام",
        "إسلام", "سنة", "سلفية", "محاضرات", "دروس", "شرح", "تفسير",
        "حديث", "فقه", "عقيدة", "تربية", "طفل", "أطفال", "اطفال",
        // English
        "documentary", "science", "history", "nature", "education",
        "discovery", "learning", "knowledge", "civilization", "national",
        "geographic", "animal", "planet", "wildlife", "cosmos", "space",
        "universe", "physics", "chemistry", "biology", "geography",
        "religious", "quran", "koran", "islam", "islamic", "lecture",
        "tutorial", "course", "academic", "research", "tech", "technology",
        // French
        "documentaire", "science", "histoire", "nature", "éducation",
        "découverte", "connaissance", "civilisation", "géographie",
        "religieux", "coran", "islamique", "cours", "académique"
    )

    /**
     * News content keywords — news, breaking, economy, politics.
     */
    private val NEWS_KEYWORDS = listOf(
        // Arabic
        "أخبار", "اخبار", "نيوز", "عاجل", "اقتصاد", "سياسة", "الجزيرة",
        "العربية", "الحرة", "سكاي", "بي بي سي", "سي إن إن", "فرانس",
        "الشرق", "غد", "يوم", "حوار", "ضيف", "مؤتمر", "تصريح", "بيان",
        // English
        "news", "breaking", "economy", "politics", "cnn", "bbc", "sky",
        "france24", "aljazeera", "alaraby", "press", "media", "report",
        "journal", "times", "today", "live news", "world news",
        // French
        "actualités", "actualite", "info", "économie", "politique",
        "presse", "journal", "monde", "direct"
    )

    /**
     * Sports content keywords — sports, football, beIN, etc.
     * (The MatchHarvester handles live match detection separately.)
     */
    private val SPORTS_KEYWORDS = listOf(
        // Arabic
        "رياضة", "رياضة", "كرة", "هدف", "بطولة", "دوري", "مباراة",
        "سباق", "ملاكمة", "مصارعة", "أولمبي", "بطولات",
        // English
        "sport", "sports", "football", "soccer", "bein", "espn",
        "goal", "match", "championship", "league", "racing", "boxing",
        "wrestling", "olympic", "tournament", "nba", "nfl", "ufc",
        // French
        "sport", "football", "match", "championnat", "ligue", "course",
        "boxe", "lutte", "olympique", "tournoi"
    )

    // ════════════════════════════════════════════════════════════════════════
    //  CLASSIFICATION CACHE
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Per-URL cache so we don't re-scan the same channel name on every
     * scroll. The cache is keyed by the channel URL (stable identity).
     * Cleared by [clearCache] when the user switches accounts.
     */
    private val classificationCache = java.util.concurrent.ConcurrentHashMap<String, ContentType>()

    /**
     * Classifies a stream into a [ContentType] based on its name + group.
     *
     * @param stream The stream to classify.
     * @return The content type (never null — defaults to ENTERTAINMENT).
     */
    fun classify(stream: StreamItem): ContentType {
        // Fast path: return cached result if available.
        val cached = classificationCache[stream.url]
        if (cached != null) return cached

        // Concatenate name + group into a single lowercase string.
        val text = "${stream.name} ${stream.group}".lowercase()

        // Check learning keywords first (highest priority for "smart
        // learning" feature — we want to catch all educational content).
        val type = when {
            LEARNING_KEYWORDS.any { text.contains(it.lowercase()) } -> ContentType.LEARNING
            NEWS_KEYWORDS.any { text.contains(it.lowercase()) } -> ContentType.NEWS
            SPORTS_KEYWORDS.any { text.contains(it.lowercase()) } -> ContentType.SPORTS
            else -> ContentType.ENTERTAINMENT
        }

        // Cache the result for future calls.
        classificationCache[stream.url] = type
        return type
    }

    /**
     * Clears the classification cache. Called by PlaylistRepository.clearSession()
     * when the user switches accounts or logs out.
     */
    fun clearCache() {
        classificationCache.clear()
    }
}
