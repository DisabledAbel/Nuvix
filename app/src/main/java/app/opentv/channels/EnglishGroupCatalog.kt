/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.channels

import app.opentv.data.db.CategoryChannelCount
import app.opentv.data.model.Category
import app.opentv.data.model.Source
import app.opentv.data.model.StreamKind

/** Shared adapter for imported M3U group-title and Xtream/Stalker category metadata. */
fun englishProviderGroups(
    categories: List<Category>,
    providers: List<Source>,
    counts: List<CategoryChannelCount>,
): List<EnglishProviderGroup> {
    val sources = providers.associateBy { it.id }
    val totals = counts.associate { ProviderGroupKey(it.sourceId, it.categoryId) to it.count }
    return categories.mapNotNull { category ->
        if (category.kind != StreamKind.LIVE) return@mapNotNull null
        val provider = sources[category.sourceId] ?: return@mapNotNull null
        val key = ProviderGroupKey(category.sourceId, category.id)
        EnglishProviderGroup(key, provider.name, category.name, totals[key] ?: 0, provider.managerRevision)
    }
}
