package io.github.ouse.deskkeys

/** Duplicate names remain ambiguous; never guess which package owns an icon. */
class AppLabelIndex(entries: List<Pair<String, String>>) {
    private val packages = entries.groupBy({ it.first.trim() }, { it.second })
        .mapValues { (_, values) -> values.distinct().singleOrNull() }
    fun find(label: String): String? = packages[TaskbarRules.appLabel(label)]
}
