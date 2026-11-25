package link.yggdrasil.yggstack.android.service

/** Result of a live Expose add/remove in the running engine. */
data class ExposeMappingResult(
    val ok: Boolean,
    val message: String? = null
)
