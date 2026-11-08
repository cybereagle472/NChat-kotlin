package com.nchat.app.data.model

/** A local ID is not proof of non-delivery: the server's acknowledgement may have been lost. */
internal fun Message.hasUnconfirmedDelivery(): Boolean = clientMessageId != null && clientMessageId == id
