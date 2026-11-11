package dev.kitlangton.scrawl

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Finishes HTTPS uploads after the app was suspended or killed, standing in for iOS's background
 * `URLSession`: scheduled whenever entries are waiting (for when the network is up, after their
 * backoff), it kicks the outbox and waits for its uploads. The outbox never runs an entry twice at
 * once, so the worker and an app in front can't double-send.
 */
class OutboxWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext as? android.app.Application ?: return Result.success()
        val scrawl = runCatching { Scrawl.shared }.getOrNull() ?: Scrawl.install(app)
        val outbox = runCatching {
            val latch = java.util.concurrent.CountDownLatch(1)
            var box: Outbox? = null
            android.os.Handler(android.os.Looper.getMainLooper()).post { box = scrawl.outboxForWorker(); latch.countDown() }
            latch.await(5, TimeUnit.SECONDS)
            box
        }.getOrNull() ?: return Result.success()
        outbox.kick(true)
        Thread.sleep(500)
        outbox.awaitIdle(9 * 60 * 1000L)
        if (outbox.hasPending()) schedule(applicationContext, outbox)
        return Result.success()
    }

    companion object {
        private const val NAME = "dev.kitlangton.scrawl.outbox"

        internal fun schedule(context: Context, outbox: Outbox) {
            val due = runCatching { outbox.nextDue() }.getOrNull() ?: return
            val delay = (due - System.currentTimeMillis()).coerceAtLeast(0)
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            runCatching { WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request) }
        }
    }
}

/**
 * Receives `<scheme>://scrawl?url=…&token=…[&session=ses_…]` setup links and
 * `<scheme>://scrawl?reset=1` reset links (also the legacy `tether` host), then gets out of the way.
 * The scheme comes from the app's `scrawlScheme` manifest placeholder.
 */
class ScrawlSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Handled only where the app installed ScrawlKit and left it enabled (iOS: `.scrawl()`).
        if (savedInstanceState == null) intent?.data?.toString()?.let { url ->
            Scrawl.installedInstance?.takeIf { it.isEnabled }?.handleSetupURL(url)
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
