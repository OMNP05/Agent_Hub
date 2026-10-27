package dev.agenthub.app.voice

/** Makes an assistant reply short enough to read aloud; the full text stays on screen (blueprint 3.2). */
object SpokenSummary {
    private const val DEFAULT_MAX = 220
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val url = Regex("""https?://\S+""")
    private val markup = Regex("""[*_`#>~|]""")
    private val sentenceEnd = Regex("""(?<=[.!?।])\s+""")

    fun shorten(text: String, maxChars: Int = DEFAULT_MAX): String {
        val plain = text
            .replace(link, "$1")
            .replace(url, "")
            .replace(markup, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (plain.isEmpty()) return ""
        if (plain.length <= maxChars) return plain

        val out = StringBuilder()
        for (sentence in plain.split(sentenceEnd)) {
            val candidate = if (out.isEmpty()) sentence else "$out $sentence"
            if (candidate.length > maxChars) break
            out.clear()
            out.append(candidate)
        }
        if (out.isNotEmpty()) return out.toString()

        // One very long sentence: cut at a word boundary instead of mid-word.
        val cut = plain.take(maxChars)
        return cut.substringBeforeLast(' ', cut).trimEnd(',', ';', ':', '-')
    }
}
