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

private val DATE_REGEX_YMD_HYPHEN = Regex("""(?:19|20)\d{2}[-_./](?:1[0-2]|0?[1-9])[-_./](?:[12]\d|3[01]|0?[1-9])""")
private val DATE_REGEX_YMD_CHINESE = Regex("""((?:19|20)\d{2})年(?:1[0-2]|0?[1-9])月(?:[12]\d|3[01]|0?[1-9])日?""")
private val DATE_REGEX_YMD_COMPACT = Regex("""\b((?:19|20)\d{2})(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\b""")
private val DATE_REGEX_YM_CHINESE = Regex("""((?:19|20)\d{2})年(?:1[0-2]|0?[1-9])月?""")
private val DATE_REGEX_YM_HYPHEN = Regex("""\b((?:19|20)\d{2})[-_./](1[0-2]|0?[1-9])\b""")
private val DATE_REGEX_YEAR_ONLY = Regex("""\b((?:19|20)\d{2})\b""")

fun extractDateFromNameOrPath(text: String?): String? {
    if (text.isNullOrBlank()) return null

    // 1. 2024-03-15 / 2024.03.15 / 2024_03_15 / 2024/03/15
    DATE_REGEX_YMD_HYPHEN.find(text)?.let { m ->
        val parts = m.value.split('-', '_', '.', '/')
        if (parts.size == 3) {
            val y = parts[0].padStart(4, '0')
            val mon = parts[1].padStart(2, '0')
            val d = parts[2].padStart(2, '0')
            return "$y-$mon-$d"
        }
    }

    // 2. 2024年3月15日
    DATE_REGEX_YMD_CHINESE.find(text)?.let { m ->
        val nums = Regex("""\d+""").findAll(m.value).map { it.value }.toList()
        if (nums.size >= 3) {
            return "${nums[0].padStart(4, '0')}-${nums[1].padStart(2, '0')}-${nums[2].padStart(2, '0')}"
        }
    }

    // 3. 20240315
    DATE_REGEX_YMD_COMPACT.find(text)?.let { m ->
        val y = m.groupValues[1]
        val mon = m.groupValues[2]
        val d = m.groupValues[3]
        return "$y-$mon-$d"
    }

    // 4. 2024年3月
    DATE_REGEX_YM_CHINESE.find(text)?.let { m ->
        val nums = Regex("""\d+""").findAll(m.value).map { it.value }.toList()
        if (nums.size >= 2) {
            return "${nums[0].padStart(4, '0')}-${nums[1].padStart(2, '0')}-01"
        }
    }

    // 5. 2024-03 / 2024.03
    DATE_REGEX_YM_HYPHEN.find(text)?.let { m ->
        val y = m.groupValues[1]
        val mon = m.groupValues[2].padStart(2, '0')
        return "$y-$mon-01"
    }

    // 6. Year only, e.g. 2024
    DATE_REGEX_YEAR_ONLY.find(text)?.let { m ->
        val y = m.groupValues[1]
        return "$y-01-01"
    }

    return null
}

fun getItemEffectiveDate(item: BaseItemDto): String? {
    val serverDate = if (isFolderItem(item)) {
        item.dateLastMediaAdded
            ?: item.dateCreated
            ?: item.premiereDate
            ?: item.userData?.lastPlayedDate
            ?: item.productionYear?.let { "$it-01-01" }
    } else {
        item.dateCreated
            ?: item.dateLastMediaAdded
            ?: item.premiereDate
            ?: item.userData?.lastPlayedDate
            ?: item.productionYear?.let { "$it-01-01" }
    }
    if (!serverDate.isNullOrBlank()) return serverDate

    // Fallback: extract date from folder name, display name, or path
    val name = getItemRawDisplayName(item)
    return extractDateFromNameOrPath(name) ?: extractDateFromNameOrPath(item.path)
}

fun parseDateToMillis(dateStr: String?): Long? {
    if (dateStr.isNullOrBlank()) return null
    val s = dateStr.trim().replace(' ', 'T')
    return runCatching {
        java.time.Instant.parse(s).toEpochMilli()
    }.recoverCatching {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    }.recoverCatching {
        java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    }.recoverCatching {
        java.time.LocalDate.parse(s).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    }.recoverCatching {
        s.toLongOrNull()
    }.getOrNull()
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

        val primarySort = sortBy.split(',').map { it.trim() }.firstOrNull { it != "IsFolder" } ?: sortBy.trim()
        val cmp = when (primarySort) {
            "SortName", "Name" -> {
                val nameA = getItemRawDisplayName(a)
                val nameB = getItemRawDisplayName(b)
                NaturalOrderComparator.compare(nameA, nameB)
            }
            "DateCreated", "DateLastMediaAdded", "PremiereDate", "DatePlayed", "DateAdded" -> {
                val timeA = parseDateToMillis(getItemEffectiveDate(a))
                val timeB = parseDateToMillis(getItemEffectiveDate(b))
                when {
                    timeA != null && timeB != null -> timeA.compareTo(timeB)
                    timeA != null -> if (isAscending) -1 else 1
                    timeB != null -> if (isAscending) 1 else -1
                    else -> 0
                }
            }
            "ProductionYear" -> {
                val yearA = a.productionYear?.takeIf { it > 0 }
                val yearB = b.productionYear?.takeIf { it > 0 }
                if (yearA == null && yearB == null) 0
                else if (yearA == null) (if (isAscending) 1 else -1)
                else if (yearB == null) (if (isAscending) -1 else 1)
                else yearA.compareTo(yearB)
            }
            "CommunityRating" -> {
                val ratingA = a.communityRating?.takeIf { it > 0f }
                val ratingB = b.communityRating?.takeIf { it > 0f }
                if (ratingA == null && ratingB == null) 0
                else if (ratingA == null) (if (isAscending) 1 else -1)
                else if (ratingB == null) (if (isAscending) -1 else 1)
                else ratingA.compareTo(ratingB)
            }
            else -> {
                val nameA = getItemRawDisplayName(a)
                val nameB = getItemRawDisplayName(b)
                NaturalOrderComparator.compare(nameA, nameB)
            }
        }

        val finalCmp = if (isAscending) cmp else -cmp
        if (finalCmp != 0) {
            finalCmp
        } else {
            NaturalOrderComparator.compare(getItemRawDisplayName(a), getItemRawDisplayName(b))
        }
    }
}
