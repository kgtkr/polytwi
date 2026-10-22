package net.kgtkr.polytwi.push

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AutopushMessage(
    val messageType: String,
    val uaid: String? = null,
    val channelID: String? = null,
    val status: Int? = null,
    val pushEndpoint: String? = null,
    val version: String? = null,
    val data: String? = null,
    val headers: Map<String, String>? = null,
    val updates: List<AutopushUpdate>? = null
)

@Serializable
data class AutopushUpdate(
    val channelID: String,
    val version: String
)

@Serializable
data class TwitterNotificationPayload(
    val title: String? = null,
    val body: String? = null,
    val icon: String? = null,
    val tag: String? = null,
    val data: NotificationData? = null
)

@Serializable
data class NotificationData(
    val url: String? = null,
    val uri: String? = null
)
