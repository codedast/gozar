package com.v2ray.ang.gozartahrim

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.TimeUnit

/**
 * GozarTahrim: drives the two background features that the Windows fork runs off TaskManager's
 * tick — the Telegram channel poll and the "connect to the best server" check.
 *
 * WorkManager's minimum periodic interval is 15 minutes; both managers additionally self-throttle
 * to their own configured intervals, so a shorter tick would just be discarded work.
 */
object GozarTahrimTaskScheduler {

    private const val WORK_NAME = "gozartahrim_periodic"
    private const val TAG = "GozarTahrimTask"

    fun schedule(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<PeriodicTask>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            LogUtil.i(AppConfig.TAG, "$TAG: periodic work scheduled")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "$TAG: failed to schedule periodic work", e)
        }
    }

    class PeriodicTask(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

        override suspend fun doWork(): Result {
            try {
                TelegramChannelNotifyManager.checkForNewPost(applicationContext)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "$TAG: telegram check failed", e)
            }
            try {
                AutoConnectManager.checkAndSwitch()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "$TAG: auto-connect check failed", e)
            }
            return Result.success()
        }
    }
}
