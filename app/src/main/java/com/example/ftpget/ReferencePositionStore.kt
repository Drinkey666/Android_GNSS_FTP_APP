package com.example.ftpget

import android.content.Context

/** Main thread saves; solver worker reads one immutable snapshot per output. */
class ReferencePositionStore(context: Context) {
    private val preferences = context.getSharedPreferences("ppp_manual_reference", Context.MODE_PRIVATE)
    // New input contract: old decimal/normal-height entries are preserved under
    // their old key but require explicit DMS re-entry, not reinterpretation.
    @Volatile var current: ReferencePosition? = ReferencePosition.decode(preferences.getString("reference_wgs84_dms", null))
        ?.takeIf { it.heightType == ReferenceHeightType.ELLIPSOIDAL }
        private set

    fun save(reference: ReferencePosition?) {
        require(reference == null || reference.heightType == ReferenceHeightType.ELLIPSOIDAL)
        val editor = preferences.edit()
        if (reference == null) editor.remove("reference_wgs84_dms") else editor.putString("reference_wgs84_dms", reference.encode())
        editor.apply()
        current = reference
    }
}
