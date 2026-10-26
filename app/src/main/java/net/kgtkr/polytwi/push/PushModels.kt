package net.kgtkr.polytwi.push

import kotlinx.serialization.Serializable

@Serializable
data class RegisterChannelRequest(
    val fcm_token: String,
    val app_server_key: String
)

@Serializable
data class RegisterChannelResponse(
    val channel_id: String,
    val push_endpoint: String,
    val app_server_key: String
)

@Serializable
data class SyncChannelsRequest(
    val fcm_token: String,
    val channel_ids: List<String>
)

@Serializable
data class SyncChannelsResponse(
    val active_channel_ids: List<String>,
    val expired_channel_ids: List<String>
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
