package ooo.lew.birdify

object Words {

    private data class SuffixRule(val regex: Regex, val base: String)

    private val rules = listOf(
        SuffixRule(Regex("\\bRepost(ed|ing|s)?\\b"), "retweet"),
        SuffixRule(Regex("\\brepost(ed|ing|s)?\\b"), "retweet"),
        SuffixRule(Regex("\\bPost(s|ed|ing)?\\b"), "tweet"),
        SuffixRule(Regex("\\bpost(s|ed|ing)?\\b"), "tweet"),
    )

    fun apply(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        for ((regex, base) in rules) {
            out = regex.replace(out) { m ->
                val suffix = m.groupValues[1]
                val b = if (m.value[0].isUpperCase()) {
                    base.replaceFirstChar { it.uppercaseChar() }
                } else {
                    base
                }
                b + suffix
            }
        }
        return out
    }
}
