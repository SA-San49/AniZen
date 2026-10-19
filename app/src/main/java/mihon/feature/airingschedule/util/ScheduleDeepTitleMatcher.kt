package mihon.feature.airingschedule.util

import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Library-side "deep search" for the airing schedule.
 *
 * Works like the Migrate screen's *Deep search mode* (strip bracketed text, drop punctuation,
 * compare cleaned titles by Levenshtein similarity), but it is designed for matching a whole
 * weekly schedule against a whole library in one pass:
 *
 *  - Every library title is parsed **once** into a [DeepTitle] and stored in an [Index]
 *    (exact-key maps + a token inverted index). Per schedule entry only a handful of
 *    candidates that share a significant token are scored, instead of every library item.
 *  - Similarity uses a banded, early-exit Levenshtein that bails out as soon as the
 *    threshold can no longer be reached, so most comparisons cost a few dozen operations.
 *  - Parsed titles are memoised, so re-filtering the schedule never re-parses a title.
 *
 * It is stricter than migration's deep search (which only has to pick the best of a few
 * search results): season / part / cour numbers and all other numbers must agree, and the
 * cleaned titles must be at least [DEFAULT_THRESHOLD] similar. Different seasons or distinct
 * sequels therefore never collide; those are left to the user's manual pick.
 */
object ScheduleDeepTitleMatcher {

    const val DEFAULT_THRESHOLD = 0.9

    private const val PARSE_CACHE_LIMIT = 8192

    /** A title reduced to the parts that identify a show. */
    class DeepTitle(
        val core: String,
        val tokens: List<String>,
        val season: Int,
        val part: Int,
        val numbers: List<String>,
    ) {
        /** Exact deep key: cleaned core text plus season and part. */
        val key: String = "$core#s$season#p$part"

        /** Tokens sorted alphabetically, for word-order-insensitive comparison. */
        val sortedCore: String = tokens.sorted().joinToString(" ")

        val isEmpty: Boolean get() = core.isEmpty()

        /** Character histogram of [core]; gives a free lower bound on edit distance. */
        internal val charHistogram: IntArray = IntArray(HISTOGRAM_SIZE).also { hist ->
            for (c in core) hist[histogramSlot(c)]++
        }
    }

    private const val HISTOGRAM_SIZE = 38

    private fun histogramSlot(c: Char): Int = when (c) {
        in 'a'..'z' -> c - 'a'
        in '0'..'9' -> 26 + (c - '0')
        ' ' -> 36
        else -> 37
    }

    /**
     * Lower bound on the Levenshtein distance from character counts alone: every edit can
     * fix at most one surplus and one missing character. O(38), no allocation.
     */
    private fun histogramLowerBound(a: IntArray, b: IntArray): Int {
        var surplus = 0
        var missing = 0
        for (i in 0 until HISTOGRAM_SIZE) {
            val d = a[i] - b[i]
            if (d > 0) surplus += d else missing -= d
        }
        return maxOf(surplus, missing)
    }

    enum class Tier { EXACT, DEEP_EXACT, FUZZY }

    data class Result<T>(val items: List<T>, val score: Double, val tier: Tier)

    // region parsing

    private val parseCache = ConcurrentHashMap<String, DeepTitle>()

    private const val ORDINAL_WORDS =
        "first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth"
    private const val ROMAN = "i{1,3}|iv|vi{0,3}|ix|x|v"

    private val SEASON_PATTERNS = listOf(
        Regex("\\b(\\d+)(?:st|nd|rd|th)\\s+season\\b"),
        Regex("\\b($ORDINAL_WORDS)\\s+season\\b"),
        Regex("\\bseason\\s*0*(\\d+)\\b"),
        Regex("\\bseason\\s+($ROMAN)\\b"),
        Regex("\\bs0*(\\d+)\\b"),
    )
    private val PART_PATTERNS = listOf(
        Regex("\\b(\\d+)(?:st|nd|rd|th)\\s+(?:part|cour)\\b"),
        Regex("\\b($ORDINAL_WORDS)\\s+(?:part|cour)\\b"),
        Regex("\\b(?:part|cour)\\s*0*(\\d+)\\b"),
        Regex("\\b(?:part|cour)\\s+($ROMAN)\\b"),
    )

    // A Roman numeral at the very end, or right before a subtitle separator, is a season.
    private val TRAILING_ROMAN = Regex("\\s(ii|iii|iv|vi{0,3}|ix|x|v)\\s*$")
    private val ROMAN_BEFORE_SEPARATOR = Regex("\\s(ii|iii|iv|vi{0,3}|ix|x|v)\\s*(?=[:\\-–—])")

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val LONG_VOWELS = listOf("ou" to "o", "oo" to "o", "uu" to "u", "aa" to "a", "ii" to "i", "ee" to "e")
    private val DROPPED_TOKENS = setOf("the", "a", "an", "tv", "season", "part", "cour")

    fun parse(title: String): DeepTitle {
        parseCache[title]?.let { return it }
        val parsed = parseUncached(title)
        if (parseCache.size >= PARSE_CACHE_LIMIT) parseCache.clear()
        parseCache[title] = parsed
        return parsed
    }

    private fun parseUncached(title: String): DeepTitle {
        // 1. Unicode folding: Ⅱ -> II, é -> e, full-width -> ASCII, then lower-case.
        var text = Normalizer.normalize(title, Normalizer.Form.NFKD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace('×', 'x')
            .replace('’', '\'')
            .replace('‘', '\'')

        // 2. Pull out season / part markers (also those inside brackets) before cleaning.
        var season: Int? = null
        for (pattern in SEASON_PATTERNS) {
            val match = pattern.find(text) ?: continue
            season = toNumber(match.groupValues[1])
            text = text.removeRange(match.range).let { " $it " }
            break
        }
        var part: Int? = null
        for (pattern in PART_PATTERNS) {
            val match = pattern.find(text) ?: continue
            part = toNumber(match.groupValues[1])
            text = text.removeRange(match.range).let { " $it " }
            break
        }

        // 3. Remove bracketed text, like deep search does ((TV), [2024], (Dub)...).
        val withoutBrackets = removeBracketed(text)
        text = if (withoutBrackets.isBlank()) text else withoutBrackets

        if (season == null) {
            text = " $text "
            val roman = ROMAN_BEFORE_SEPARATOR.find(text) ?: TRAILING_ROMAN.find(text)
            if (roman != null) {
                season = toNumber(roman.groupValues[1])
                text = text.removeRange(roman.groups[1]!!.range)
            }
        }

        // 4. Strip punctuation, fold romaji long vowels, drop filler words.
        val tokens = text.split(NON_ALNUM)
            .asSequence()
            .filter { it.isNotEmpty() }
            .map { foldLongVowels(it) }
            .filter { it !in DROPPED_TOKENS }
            .toList()
        val numbers = tokens.filter { token -> token.all(Char::isDigit) }
            .map { it.trimStart('0').ifEmpty { "0" } }
            .sorted()

        return DeepTitle(
            core = tokens.joinToString(" "),
            tokens = tokens,
            season = season ?: 1,
            part = part ?: 1,
            numbers = numbers,
        )
    }

    private fun foldLongVowels(token: String): String {
        if (token.length < 3 || token.all(Char::isDigit)) return token
        var out = token
        for ((from, to) in LONG_VOWELS) out = out.replace(from, to)
        return out
    }

    private fun removeBracketed(text: String): String {
        var depth = 0
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '(', '[', '{', '<', '【', '「', '『' -> depth++
                ')', ']', '}', '>', '】', '」', '』' -> if (depth > 0) depth-- else sb.append(' ')
                else -> if (depth == 0) sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun toNumber(raw: String): Int? = when (raw.lowercase(Locale.ROOT)) {
        "first", "i" -> 1
        "second", "ii" -> 2
        "third", "iii" -> 3
        "fourth", "iv" -> 4
        "fifth", "v" -> 5
        "sixth", "vi" -> 6
        "seventh", "vii" -> 7
        "eighth", "viii" -> 8
        "ninth", "ix" -> 9
        "tenth", "x" -> 10
        else -> raw.toIntOrNull()
    }

    // endregion

    // region scoring

    /**
     * Similarity between two parsed titles in 0..1, or 0 when they can't be the same show
     * (different season/part/numbers). Returns early once [threshold] is unreachable.
     */
    fun similarity(a: DeepTitle, b: DeepTitle, threshold: Double = DEFAULT_THRESHOLD): Double {
        if (a.isEmpty || b.isEmpty) return 0.0
        if (a.season != b.season || a.part != b.part) return 0.0
        if (a.numbers != b.numbers) return 0.0
        if (a.core == b.core) return 1.0
        // Cheap rejection before any Levenshtein work (also valid for the sorted-token form,
        // which has exactly the same characters).
        val maxLen = maxOf(a.core.length, b.core.length)
        val maxDist = ((1.0 - threshold) * maxLen).toInt()
        if (kotlin.math.abs(a.core.length - b.core.length) > maxDist) return 0.0
        if (histogramLowerBound(a.charHistogram, b.charHistogram) > maxDist) return 0.0
        val direct = boundedSimilarity(a.core, b.core, threshold)
        if (direct >= 1.0) return direct
        val sorted = if (a.sortedCore != a.core || b.sortedCore != b.core) {
            boundedSimilarity(a.sortedCore, b.sortedCore, threshold)
        } else {
            0.0
        }
        return maxOf(direct, sorted)
    }

    /** True if two raw titles are the same show by deep matching. */
    fun matches(title1: String?, title2: String?, threshold: Double = DEFAULT_THRESHOLD): Boolean {
        if (title1.isNullOrBlank() || title2.isNullOrBlank()) return false
        return similarity(parse(title1), parse(title2), threshold) >= threshold
    }

    /**
     * Levenshtein similarity (1 - distance / maxLen) using a diagonal band of width
     * maxAllowedDistance and an early exit when every cell in a row exceeds it.
     * Returns 0.0 when the result would be below [threshold].
     */
    internal fun boundedSimilarity(s1: String, s2: String, threshold: Double): Double {
        if (s1 == s2) return 1.0
        val maxLen = maxOf(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        val maxDist = ((1.0 - threshold) * maxLen).toInt()
        if (kotlin.math.abs(s1.length - s2.length) > maxDist) return 0.0
        if (maxDist == 0) return 0.0

        val n = s1.length
        val m = s2.length
        val big = maxDist + 1
        var prev = IntArray(m + 1) { if (it <= maxDist) it else big }
        var curr = IntArray(m + 1)
        for (i in 1..n) {
            val from = maxOf(1, i - maxDist)
            val to = minOf(m, i + maxDist)
            curr.fill(big)
            curr[0] = if (i <= maxDist) i else big
            var rowMin = curr[0]
            val c1 = s1[i - 1]
            for (j in from..to) {
                val cost = if (c1 == s2[j - 1]) 0 else 1
                var v = prev[j - 1] + cost
                val del = prev[j] + 1
                if (del < v) v = del
                val ins = curr[j - 1] + 1
                if (ins < v) v = ins
                if (v > big) v = big
                curr[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > maxDist) return 0.0
            val tmp = prev
            prev = curr
            curr = tmp
        }
        val distance = prev[m]
        if (distance > maxDist) return 0.0
        val similarity = 1.0 - distance.toDouble() / maxLen
        return if (similarity >= threshold) similarity else 0.0
    }

    // endregion

    /**
     * Pre-built lookup over a library. Build it once per library change (off the main thread),
     * then call [find] for every schedule entry.
     */
    class Index<T>(
        items: List<Pair<T, List<String>>>,
        private val threshold: Double = DEFAULT_THRESHOLD,
    ) {
        private val refs = ArrayList<T>(items.size)
        private val exact = HashMap<String, MutableList<Int>>()
        private val deepExact = HashMap<String, MutableList<Int>>()
        private val parsed = ArrayList<DeepTitle>()
        private val parsedOwner = ArrayList<Int>()
        private val postingsBuilder = HashMap<String, MutableList<Int>>()
        private val tokenPostings: Map<String, IntArray>

        init {
            items.forEachIndexed { refIdx, (ref, titles) ->
                refs.add(ref)
                for (title in titles.asSequence().filter { it.isNotBlank() }.distinct()) {
                    for (key in ScheduleTitleMatcher.normalizedKeys(title)) {
                        exact.getOrPut(key) { ArrayList(1) }.addIfAbsent(refIdx)
                    }
                    val deep = parse(title)
                    if (deep.isEmpty) continue
                    deepExact.getOrPut(deep.key) { ArrayList(1) }.addIfAbsent(refIdx)
                    val parsedIdx = parsed.size
                    parsed.add(deep)
                    parsedOwner.add(refIdx)
                    for (token in indexTokens(deep)) {
                        postingsBuilder.getOrPut(token) { ArrayList(2) }.add(parsedIdx)
                    }
                }
            }
            tokenPostings = postingsBuilder.mapValuesTo(HashMap(postingsBuilder.size)) { it.value.toIntArray() }
            postingsBuilder.clear()
        }

        val size: Int get() = refs.size

        fun find(candidateTitles: Collection<String>): Result<T>? {
            val titles = candidateTitles.filter { it.isNotBlank() }.distinct()
            if (titles.isEmpty() || refs.isEmpty()) return null

            // Tier 1: the established normalized-key match (fast hash lookups).
            val exactHits = LinkedHashSet<Int>()
            for (title in titles) {
                for (key in ScheduleTitleMatcher.normalizedKeys(title)) {
                    exact[key]?.let(exactHits::addAll)
                }
            }
            if (exactHits.isNotEmpty()) return Result(exactHits.map(refs::get), 1.0, Tier.EXACT)

            // Tier 2: deep-cleaned exact key (brackets, accents, long vowels, filler words).
            val deepTitles = titles.map(::parse).filterNot { it.isEmpty }
            val deepHits = LinkedHashSet<Int>()
            for (deep in deepTitles) deepExact[deep.key]?.let(deepHits::addAll)
            if (deepHits.isNotEmpty()) return Result(deepHits.map(refs::get), 1.0, Tier.DEEP_EXACT)

            // Tier 3: fuzzy similarity, only against candidates sharing enough significant
            // tokens. A >= 90% similar title keeps most of its words, so candidates sharing
            // fewer than half of them are skipped without running Levenshtein at all.
            var bestScore = 0.0
            val best = LinkedHashSet<Int>()
            val scored = BooleanArray(parsed.size)
            val shared = IntArray(parsed.size)
            val touched = IntArray(parsed.size)
            for (deep in deepTitles) {
                val queryTokens = indexTokens(deep)
                val needed = maxOf(1, (queryTokens.size + 1) / 2)
                var touchedCount = 0
                for (token in queryTokens) {
                    val postings = tokenPostings[token] ?: continue
                    for (i in postings.indices) {
                        val parsedIdx = postings[i]
                        if (shared[parsedIdx]++ == 0) touched[touchedCount++] = parsedIdx
                    }
                }
                for (t in 0 until touchedCount) {
                    val parsedIdx = touched[t]
                    val count = shared[parsedIdx]
                    shared[parsedIdx] = 0
                    if (count < needed || scored[parsedIdx]) continue
                    scored[parsedIdx] = true
                    val score = similarity(deep, parsed[parsedIdx], threshold)
                    if (score < threshold) continue
                    val owner = parsedOwner[parsedIdx]
                    if (score > bestScore) {
                        bestScore = score
                        best.clear()
                        best.add(owner)
                    } else if (score == bestScore) {
                        best.add(owner)
                    }
                }
            }
            if (best.isEmpty()) return null
            return Result(best.map(refs::get), bestScore, Tier.FUZZY)
        }

        private fun indexTokens(deep: DeepTitle): Set<String> {
            val significant = deep.tokens.filter { it.length >= 3 && it !in COMMON_TOKENS }
            val chosen = significant.ifEmpty { deep.tokens }
            // A prefix key keeps single-word titles reachable when the only word has a typo.
            return (chosen + "^" + deep.core.take(4)).toSet()
        }

        private fun MutableList<Int>.addIfAbsent(value: Int) {
            if (isEmpty() || last() != value) add(value)
        }
    }

    private val COMMON_TOKENS = setOf(
        "and", "with", "for", "from", "that", "this", "you", "your", "not", "but", "are",
        "was", "who", "how", "what", "can", "his", "her", "its", "into", "about", "after",
        "world", "another", "life", "love", "story", "new", "girl", "boy", "way", "one",
        "wo", "ga", "ni", "na", "no", "to", "wa", "de",
    )
}
