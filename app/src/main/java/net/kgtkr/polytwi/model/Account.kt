package net.kgtkr.polytwi.model

import kotlinx.serialization.Serializable

@Serializable
data class Account(
    val id: String,
    val name: String,
    val profileName: String,
    val endpoint: String? = null,
    val channelId: String? = null,
    val p256dhBase64: String? = null,
    val authBase64: String? = null,
    val privateKeyPkcs8Base64: String? = null,
    val appServerKeyBase64: String? = null
)
