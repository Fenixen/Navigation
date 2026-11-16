package cz.fenixen.navigation.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import cz.fenixen.navigation.MainActivity
import cz.fenixen.navigation.NavApp
import cz.fenixen.navigation.R
import cz.fenixen.navigation.core.format.CzechFormat
import cz.fenixen.navigation.core.navigation.NavigationState
import kotlinx.coroutines.launch

/**
 * Keeps navigation alive with the screen off / app in background (Android kills or throttles
 * background location otherwise). Type "location" is mandatory since Android 14.
 *
 * The service owns no navigation logic – it just keeps the process important, holds
 * location demand and mirrors the session state into an ongoing notification.
 */
class NavigationService : LifecycleService() {
    private var releaseLocation: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val graph = (application as NavApp).graph
        if (intent?.action == ACTION_STOP) {
            graph.session.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(graph.session.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        if (releaseLocation == null) {
            releaseLocation = graph.locationCoordinator.acquire()
            lifecycleScope.launch {
                graph.session.state.collect { state ->
                    if (state is NavigationState.Navigating) {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(state))
                    } else {
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocation?.invoke()
        releaseLocation = null
        super.onDestroy()
    }

    private fun buildNotification(state: NavigationState): android.app.Notification {
        val nav = state as? NavigationState.Navigating
        val progress = nav?.progress
        val title = when {
            nav == null -> getString(R.string.app_name)
            nav.isRerouting -> getString(R.string.rerouting)
            progress != null && nav.upcoming != null ->
                "${CzechFormat.distance(progress.distanceToUpcoming)} · ${nav.upcoming!!.instruction}"
            else -> getString(R.string.navigating_to, nav.destination.name)
        }
        val text = progress?.let {
            val eta = System.currentTimeMillis() + (it.durationRemaining * 1000).toLong()
            getString(
                R.string.notification_eta,
                CzechFormat.clock(eta),
                CzechFormat.duration(it.durationRemaining),
                CzechFormat.distance(it.distanceRemaining),
            )
        } ?: nav?.destination?.name.orEmpty()

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_navigation)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_close, getString(R.string.end_navigation), stop)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_navigation), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "NavigationService"
        private const val CHANNEL_ID = "navigation"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "cz.fenixen.navigation.STOP"

        /** Starting a foreground service can be refused when the app is in the background. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java))
            } catch (e: IllegalStateException) {
                // Např. ForegroundServiceStartNotAllowedException – navigace jede dál, dokud je UI aktivní.
                Log.w(TAG, "Could not start foreground service", e)
            }
        }
    }
}
