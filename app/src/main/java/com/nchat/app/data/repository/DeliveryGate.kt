package com.nchat.app.data.repository

/** Prevents simultaneous local attempts for the same client message id. */
internal class DeliveryGate {
    private val inFlight = mutableSetOf<String>()

    @Synchronized
    fun tryAcquire(clientId: String): Boolean = inFlight.add(clientId)

    @Synchronized
    fun release(clientId: String) {
        inFlight.remove(clientId)
    }
}
