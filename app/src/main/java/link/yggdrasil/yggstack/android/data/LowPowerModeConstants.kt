package link.yggdrasil.yggstack.android.data

/**
 * Constants for low power mode feature
 */
object LowPowerModeConstants {
    val TIMEOUT_OPTIONS = listOf(
        60,    // 1 minute
        120,   // 2 minutes (default)
        180,   // 3 minutes
        300,   // 5 minutes
    )
    
    const val DEFAULT_TIMEOUT_SECONDS = 120
    const val MAX_QUEUED_CONNECTIONS = 10
    const val MAX_CONNECTION_HOLD_TIME_SECONDS = 10
    const val IDLE_CHECK_INTERVAL_SECONDS = 10
}
