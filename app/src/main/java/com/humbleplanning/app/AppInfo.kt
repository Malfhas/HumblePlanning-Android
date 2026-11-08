package com.humbleplanning.app

object AppInfo {
    /** Sent to the server in X-App-Version, compared with its minimum app version: versionName without suffix. */
    val VERSION: String = BuildConfig.VERSION_NAME.substringBefore('-')

    /** Scheme of the sign-in links (QR code of the web profile). */
    const val QR_SCHEME = "humbleplanning"

    const val WEBSITE_URL = "https://humbleplanning.com/"
    const val SOURCE_URL = "https://github.com/Malfhas/HumblePlanning"
}
