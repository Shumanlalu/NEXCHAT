package com.nexchat.app.core.settings

import android.content.SharedPreferences

/**
 * Ordering watermark for GROUP_META envelopes, persisted outside Room so no
 * schema/version change is needed. Comparison is by (timestamp, id) from the
 * relay envelope, never by the sender's clock.
 */
data class GroupMetadataWatermark(val timestamp: Long, val id: String) : Comparable<GroupMetadataWatermark> {
    override fun compareTo(other: GroupMetadataWatermark): Int =
        compareValuesBy(this, other, { it.timestamp }, { it.id })
}

class GroupMetadataWatermarkStore(private val prefs: SharedPreferences) {
    fun shouldApply(groupId: String, candidate: GroupMetadataWatermark): Boolean {
        val stored = read(groupId) ?: return true
        return candidate > stored
    }

    fun markApplied(groupId: String, candidate: GroupMetadataWatermark) {
        prefs.edit()
            .putLong("${KEY_TIMESTAMP_PREFIX}$groupId", candidate.timestamp)
            .putString("${KEY_ID_PREFIX}$groupId", candidate.id)
            .apply()
    }

    private fun read(groupId: String): GroupMetadataWatermark? {
        val timestamp = prefs.getLong("${KEY_TIMESTAMP_PREFIX}$groupId", Long.MIN_VALUE)
        val id = prefs.getString("${KEY_ID_PREFIX}$groupId", null) ?: return null
        return GroupMetadataWatermark(timestamp, id)
    }

    private companion object {
        const val KEY_TIMESTAMP_PREFIX = "group_meta_watermark_ts:"
        const val KEY_ID_PREFIX = "group_meta_watermark_id:"
    }
}
