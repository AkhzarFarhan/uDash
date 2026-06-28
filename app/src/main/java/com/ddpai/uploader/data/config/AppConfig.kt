package com.ddpai.uploader.data.config

data class AppConfig(
    val youtubeClientId: String = "",
    val youtubeClientSecret: String = "",
    val uploadPrivacy: String = "private", // private | unlisted | public
    val homeWifiBssidOptional: String = "",
    val deleteAfterUpload: Boolean = true,
    val wifiAutoStart: Boolean = true,
    val dashcamGateway: String = "193.168.0.1",
    val maxRetries: Int = 5
)
