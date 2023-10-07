package eu.kanade.tachiyomi.animeextension.all.jellyfin

import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class JellyfinAuthenticator(
    private val preferences: SharedPreferences,
    private val baseUrl: String,
    private val client: OkHttpClient,
) {

    private val json: Json by injectLazy()

    fun login(username: String, password: String): Pair<String?, String?> {
        return runCatching {
            val authResult = authenticateWithPassword(username, password)
            val key = authResult.AccessToken
            val userId = authResult.SessionInfo.UserId
            saveLogin(key, userId)
            Pair(key, userId)
        }.getOrElse {
            Log.e(LOG_TAG, it.stackTraceToString())
            Pair(null, null)
        }
    }

    private fun authenticateWithPassword(username: String, password: String): LoginResponse {
        var deviceId = getPrefDeviceId()
        if (deviceId.isNullOrEmpty()) {
            deviceId = getRandomString()
            setPrefDeviceId(deviceId)
        }
        val aniyomiVersion = AppInfo.getVersionName()
        val androidVersion = Build.VERSION.RELEASE
        val authHeader = Headers.headersOf(
            "X-Emby-Authorization",
            "MediaBrowser Client=\"$CLIENT\", Device=\"Android $androidVersion\", DeviceId=\"$deviceId\", Version=\"$aniyomiVersion\"",
        )
        val body = json.encodeToString(
            buildJsonObject {
                put("Username", username)
                put("Pw", password)
            },
        ).toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = POST("$baseUrl/Users/authenticatebyname", headers = authHeader, body = body)
        return client.newCall(request).execute().parseAs()
    }

    private fun getRandomString(): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..172)
            .map { allowedChars.random() }
            .joinToString("")
    }

    private fun saveLogin(key: String, userId: String) {
        preferences.edit()
            .putString(JFConstants.APIKEY_KEY, key)
            .putString(JFConstants.USERID_KEY, userId)
            .apply()
    }

    private fun getPrefDeviceId(): String? = preferences.getString(
        DEVICEID_KEY,
        null,
    )

    private fun setPrefDeviceId(value: String) = preferences.edit().putString(
        DEVICEID_KEY,
        value,
    ).apply()

    private inline fun <reified T> Response.parseAs(transform: (String) -> String = { it }): T {
        val responseBody = use { transform(it.body.string()) }
        return json.decodeFromString(responseBody)
    }

    companion object {
        private const val DEVICEID_KEY = "device_id"
        private const val CLIENT = "Aniyomi"
        private const val LOG_TAG = "JellyfinAuthenticator"
    }
}
