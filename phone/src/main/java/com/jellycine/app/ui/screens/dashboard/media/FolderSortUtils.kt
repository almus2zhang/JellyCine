package com.jellycine.app.ui.screens.dashboard.media

import com.jellycine.data.model.BaseItemDto

object NaturalOrderComparator : Comparator<String> {
    override fun compare(s1: String?, s2: String?): Int {
        if (s1 == null && s2 == null) return 0
        if (s1 == null) return -1
        if (s2 == null) return 1

        var i1 = 0
        var i2 = 0
        val len1 = s1.length
        val len2 = s2.length
        var firstZeroDiff = 0

        while (i1 < len1 && i2 < len2) {
            val c1 = s1[i1]
            val c2 = s2[i2]

            if (c1.isDigit() && c2.isDigit()) {
                val start1 = i1
                while (i1 < len1 && s1[i1].isDigit()) i1++
                val start2 = i2
                while (i2 < len2 && s2[i2].isDigit()) i2++

                val numStr1 = s1.substring(start1, i1)
                val numStr2 = s2.substring(start2, i2)

                val nonZero1 = numStr1.trimStart('0')
                val nonZero2 = numStr2.trimStart('0')

                val lenDiff = nonZero1.length - nonZero2.length
                if (lenDiff != 0) {
                    return lenDiff
                }

                val cmp = nonZero1.compareTo(nonZero2)
                if (cmp != 0) {
                    return cmp
                }

                if (firstZeroDiff == 0) {
                    firstZeroDiff = numStr1.length - numStr2.length
                }
            } else {
                val cmp = c1.lowercaseChar().compareTo(c2.lowercaseChar())
                if (cmp != 0) {
                    return cmp
                }
                i1++
                i2++
            }
        }

        if (i1 < len1) return 1
        if (i2 < len2) return -1

        if (firstZeroDiff != 0) return firstZeroDiff

        return s1.compareTo(s2)
    }
}

fun getItemRawDisplayName(item: BaseItemDto): String {
    val fromPath = item.path?.trim()?.trimEnd('/', '\\')?.let { p ->
        p.substringAfterLast('/').substringAfterLast('\\').ifBlank { null }
    }
    return fromPath ?: item.name ?: item.originalTitle.orEmpty()
}

fun isFolderItem(item: BaseItemDto): Boolean {
    return item.isFolder == true ||
        item.type.equals("Folder", ignoreCase = true) ||
        item.type.equals("CollectionFolder", ignoreCase = true) ||
        item.type.equals("Directory", ignoreCase = true) ||
        item.type.equals("UserView", ignoreCase = true)
}

fun sortFolderItems(
    items: List<BaseItemDto>,
    sortBy: String,
    sortOrder: String
): List<BaseItemDto> {
    val isAscending = sortOrder.equals("Ascending", ignoreCase = true)
    return items.sortedWith { a, b ->
        val isFolderA = isFolderItem(a)
        val isFolderB = isFolderItem(b)

        // Folders always stay at the top
        if (isFolderA != isFolderB) {
            return@sortedWith if (isFolderA) -1 else 1
        }

        val cleanSortBy = sortBy.removePrefix("IsFolder,").trim()
        val cmp = when (cleanSortBy) {
            "SortName", "Name" -> {
                val nameA = getItemRawDisplayName(a)
                val nameB = getItemRawDisplayName(b)
                NaturalOrderComparator.compare(nameA, nameB)
            }
            "DateCreated" -> {
                val dateA = a.dateCreated ?: a.premiereDate
                val dateB = b.dateCreated ?: b.premiereDate
                val dateCmp = when {
                    dateA == null && dateB == null -> 0
                    dateA == null -> -1
                    dateB == null -> 1
                    else -> dateA.compareTo(dateB)
                }
                if (dateCmp != 0) dateCmp else {
                    NaturalOrderComparator.compare(getItemRawDisplayName(a), getItemRawDisplayName(b))
                }
            }
            "ProductionYear" -> {
                val yearA = a.productionYear ?: 0
                val yearB = b.productionYear ?: 0
                val yearCmp = yearA.compareTo(yearB)
                if (yearCmp != 0) yearCmp else {
                    NaturalOrderComparator.compare(getItemRawDisplayName(a), getItemRawDisplayName(b))
                }
            }
            "CommunityRating" -> {
                val ratingA = a.communityRating ?: 0f
                val ratingB = b.communityRating ?: 0f
                val ratingCmp = ratingA.compareTo(ratingB)
                if (ratingCmp != 0) ratingCmp else {
                    NaturalOrderComparator.compare(getItemRawDisplayName(a), getItemRawDisplayName(b))
                }
            }
            else -> {
                val nameA = getItemRawDisplayName(a)
                val nameB = getItemRawDisplayName(b)
                NaturalOrderComparator.compare(nameA, nameB)
            }
        }

        if (isAscending) cmp else -cmp
    }
}
