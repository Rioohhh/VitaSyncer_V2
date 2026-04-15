package nl.vitasyncer.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.LocalDate

class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "SyncWorker"
        const val WORK_NAME = "vitasyncer_periodic_sync"
    }

    override suspend fun doWork(): Result {
        val store = CredentialStore(applicationContext)
        val hcManager = HealthConnectManager(applicationContext)

        val heeftAuth = store.cookieString.isNotBlank() ||
            (store.username.isNotBlank() && store.password.isNotBlank())

        if (!heeftAuth) {
            store.lastSyncStatus = "⚠️ Vul eerst inloggegevens of cookies in."
            return Result.failure()
        }

        if (!hcManager.isAvailable()) {
            store.lastSyncStatus = "❌ Health Connect niet beschikbaar."
            return Result.failure()
        }

        if (!hcManager.hasPermissions()) {
            store.lastSyncStatus = "⚠️ Health Connect toestemmingen niet verleend. Open de app."
            return Result.failure()
        }

        val lastSyncEpoch = store.lastSyncTimestamp
        val fromDate = if (lastSyncEpoch > 0)
            LocalDate.ofEpochDay(lastSyncEpoch / 86400).minusDays(1)
        else
            LocalDate.now().minusDays(30)

        val api = VirtuagymApi(store.username, store.password,
            store.cookieString.ifBlank { null })
        val result = api.getBodyMetrics(fromDate)

        return when (result) {
            is ApiResult.Error -> {
                store.lastSyncStatus = "❌ ${result.message}"
                Result.retry()
            }
            is ApiResult.Success -> {
                val newEntries = result.entries.filter { it.epochSeconds > lastSyncEpoch }
                if (newEntries.isEmpty()) {
                    store.lastSyncStatus = "✅ Geen nieuwe metingen gevonden."
                    return Result.success()
                }

                val grouped = newEntries.groupBy { it.dateStr }
                val statusLines = mutableListOf<String>()
                var latestEpoch = lastSyncEpoch

                for ((_, group) in grouped.entries.sortedBy { it.key }) {
                    statusLines += hcManager.writeMetricGroup(group, store)
                    val maxEpoch = group.maxOf { it.epochSeconds }
                    if (maxEpoch > latestEpoch) latestEpoch = maxEpoch
                }

                store.lastSyncTimestamp = latestEpoch
                store.lastSyncStatus = statusLines.joinToString("\n---\n")
                Log.d(TAG, "Sync klaar")
                Result.success()
            }
        }
    }
}
