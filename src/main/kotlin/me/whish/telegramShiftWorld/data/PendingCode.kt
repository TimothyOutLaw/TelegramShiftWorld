package me.whish.telegramShiftWorld.data

import java.util.UUID

data class PendingCode(
    val playerUuid: UUID,
    val playerName: String,
    val expiryTime: Long
)
