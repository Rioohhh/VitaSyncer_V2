package nl.vitasyncer.app

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class BodyMetricEntry(
    val definitionId: Int,
    val value: Double,
    val epochSeconds: Long,
    val dateStr: String
)

sealed class ApiResult {
    data class Success(val entries: List<BodyMetricEntry>, val rawJson: String) : ApiResult()
    data class Error(val message: String) : ApiResult()
}

class VirtuagymApi(
    private val username: String,
    private val password: String,
    private val cookieString: String?   // bijv. "virtuagym_k=XXX; virtuagym_u=YYY"
) {
    companion object {
        private const val TAG = "VirtuagymApi"
        private const val BASE_URL = "https://api.virtuagym.com/api/v0"
    }

    private val basicAuthHeader: String
        get() {
            val credentials = "$username:$password"
            return "Basic " + Base64.encodeToString(
                credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
        }

    fun getBodyMetrics(fromDate: LocalDate? = null): ApiResult {
        // Probeer eerst cookie-auth, daarna Basic Auth
        val cookieResult = if (!cookieString.isNullOrBlank()) {
            tryWithCookies(fromDate)
        } else null

        if (cookieResult is ApiResult.Success) return cookieResult

        // Fallback: Basic Auth
        return tryBasicAuth(fromDate, cookieResult)
    }

    private fun tryWithCookies(fromDate: LocalDate?): ApiResult {
        return try {
            // Haal user ID op uit cookie string
            val userId = extractUserId(cookieString ?: "") ?: return ApiResult.Error("Geen virtuagym_u in cookies")

            val params = mutableListOf<String>()
            if (fromDate != null) {
                params.add("start=${fromDate.format(DateTimeFormatter.ISO_LOCAL_DATE)}")
            }
            params.add("limit=100")

            // Gebruik user-specifiek endpoint met cookie-auth
            val urlStr = "$BASE_URL/member/$userId/bodymetric" +
                if (params.isNotEmpty()) "?" + params.joinToString("&") else ""

            Log.d(TAG, "Cookie-auth GET $urlStr")
            makeRequest(urlStr, useBasicAuth = false)
        } catch (e: Exception) {
            ApiResult.Error("Cookie-auth fout: ${e.message}")
        }
    }

    private fun tryBasicAuth(fromDate: LocalDate?, previousError: ApiResult?): ApiResult {
        if (username.isBlank() || password.isBlank()) {
            return previousError ?: ApiResult.Error("Geen inloggegevens ingesteld")
        }

        return try {
            val params = mutableListOf<String>()
            if (fromDate != null) {
                params.add("start=${fromDate.format(DateTimeFormatter.ISO_LOCAL_DATE)}")
            }
            params.add("limit=100")

            val urlStr = "$BASE_URL/bodymetric" +
                if (params.isNotEmpty()) "?" + params.joinToString("&") else ""

            Log.d(TAG, "Basic Auth GET $urlStr")
            makeRequest(urlStr, useBasicAuth = true)
        } catch (e: Exception) {
            ApiResult.Error("Basic Auth fout: ${e.message}")
        }
    }

    private fun makeRequest(urlStr: String, useBasicAuth: Boolean): ApiResult {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000

        if (useBasicAuth) {
            conn.setRequestProperty("Authorization", basicAuthHeader)
        }

        if (!cookieString.isNullOrBlank()) {
            conn.setRequestProperty("Cookie", cookieString)
        }

        val responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) conn.inputStream
                     else (conn.errorStream ?: conn.inputStream)
        val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()

        Log.d(TAG, "HTTP $responseCode: ${body.take(200)}")

        return if (responseCode in 200..299) {
            ApiResult.Success(parseResponse(body), body)
        } else {
            ApiResult.Error("HTTP $responseCode:\n$body")
        }
    }

    /** Haalt virtuagym_u=NNNNN op uit de cookie string */
    private fun extractUserId(cookies: String): String? {
        return cookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("virtuagym_u=") }
            ?.removePrefix("virtuagym_u=")
            ?.trim()
    }

    private fun parseResponse(json: String): List<BodyMetricEntry> {
        val entries = mutableListOf<BodyMetricEntry>()
        return try {
            val root = JSONObject(json)
            val resultArray = root.optJSONArray("result")
                ?: root.optJSONArray("bodymetrics")
                ?: return entries

            for (i in 0 until resultArray.length()) {
                val obj = resultArray.optJSONObject(i) ?: continue
                val defId = obj.optInt("bodymetric_definition_id", -1)
                if (defId < 0) continue

                val value = try {
                    obj.getDouble("value")
                } catch (e: Exception) {
                    obj.optString("value", "0").toDoubleOrNull() ?: continue
                }

                val created = obj.optString("created", obj.optString("date", ""))
                val epochSeconds = parseCreatedToEpoch(created)
                val dateStr = created.take(10).ifBlank {
                    LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                }

                entries.add(BodyMetricEntry(defId, value, epochSeconds, dateStr))
            }
            entries
        } catch (e: Exception) {
            Log.e(TAG, "Parse fout", e)
            entries
        }
    }

    private fun parseCreatedToEpoch(dateStr: String): Long {
        if (dateStr.isBlank()) return System.currentTimeMillis() / 1000
        return try {
            Instant.parse(
                if (dateStr.contains("T")) dateStr.replace(" ", "T")
                else "${dateStr}T00:00:00Z"
            ).epochSecond
        } catch (e1: Exception) {
            try {
                LocalDate.parse(dateStr.take(10))
                    .atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
            } catch (e2: Exception) {
                System.currentTimeMillis() / 1000
            }
        }
    }
}
