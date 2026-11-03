// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.accessibility

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/** On-demand diagnostics: IDs/actions/extra-data keys for Netflix's own title controls only. */
object NetflixButtonInspector {
    fun inspect(root: AccessibilityNodeInfo): List<String> {
        val result = mutableListOf<String>()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (++visited > 1800 || depth > 60) return
            val label = node.contentDescription?.toString().orEmpty()
            if (node.className?.toString() in setOf("android.widget.Button", "android.widget.ToggleButton", "android.widget.ImageButton") &&
                (label.startsWith("Play ") || label.startsWith("Resume ") || label in setOf("Share", "Download", "My List"))) {
                val extras = node.extras
                val keys = extras.keySet().sorted()
                val values = keys.mapNotNull { key ->
                    @Suppress("DEPRECATION") val value = extras.get(key)
                    val text = value?.toString().orEmpty()
                    val url = Regex("https?://(?:www\\.)?netflix\\.com/(?:[a-z-]+/)?(?:title|watch)/[0-9]+").find(text)?.value
                    when {
                        url != null -> "$key=$url"
                        key.contains(Regex("(?i)video.?id|netflix.?id|entity.?id|content.?id")) && text.matches(Regex("[0-9]{6,10}")) -> "$key=$text"
                        else -> null
                    }
                }
                result += "$label: widget=${node.viewIdResourceName}; actions=${node.actionList.map { it.id }}; extraKeys=$keys; contentIds=$values; requestedExtraKeys=${node.availableExtraData}; uniqueIdPresent=${Build.VERSION.SDK_INT >= 33 && node.uniqueId != null}"
            }
            for (i in 0 until node.childCount) {
                if (visited >= 1800) break
                val child = node.getChild(i) ?: continue
                try { walk(child, depth + 1) } finally { NetflixTreeReader.release(child) }
            }
        }
        walk(root, 0)
        return result
    }
}
