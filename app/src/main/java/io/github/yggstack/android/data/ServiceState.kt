package io.github.yggstack.android.data

/**
 * Service state model
 */
sealed class ServiceState {
    object Stopped : ServiceState()
    object Starting : ServiceState()
    object Running : ServiceState()
    object Stopping : ServiceState()
    data class Error(val message: String) : ServiceState()
}

