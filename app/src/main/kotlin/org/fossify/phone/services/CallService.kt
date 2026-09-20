package org.fossify.phone.services

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.fossify.commons.extensions.canUseFullScreenIntent
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_POST_NOTIFICATIONS
import org.fossify.phone.activities.CallActivity
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.isOutgoing
import org.fossify.phone.extensions.keyguardManager
import org.fossify.phone.extensions.powerManager
import org.fossify.phone.helpers.CallManager
import org.fossify.phone.helpers.CallNotificationManager
import org.fossify.phone.helpers.NoCall
import org.fossify.phone.models.Events
import org.greenrobot.eventbus.EventBus

class CallService : InCallService() {
    private val callNotificationManager by lazy { CallNotificationManager(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private val proximitySensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) }
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var pendingRouteChange: Runnable? = null
    private var currentAudioState: CallAudioState? = null

    private val callListener = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                callNotificationManager.cancelNotification()
            } else {
                callNotificationManager.setupNotification()
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.onCallAdded(call)
        CallManager.inCallService = this
        startAutomaticSpeakerRouting()
        call.registerCallback(callListener)

        // Incoming/Outgoing (locked): high priority (FSI)
        // Incoming (unlocked): if user opted in, low priority ➜ manual activity start, otherwise high priority (FSI)
        // Outgoing (unlocked): low priority ➜ manual activity start
        val isIncoming = !call.isOutgoing()
        val isDeviceLocked = !powerManager.isInteractive || keyguardManager.isDeviceLocked
        val lowPriority = when {
            isIncoming && isDeviceLocked -> false
            !isIncoming && isDeviceLocked -> false
            isIncoming && !isDeviceLocked -> config.alwaysShowFullscreen
            else -> true
        }

        callNotificationManager.setupNotification(lowPriority)
        if (
            lowPriority
            || !hasPermission(PERMISSION_POST_NOTIFICATIONS)
            || !canUseFullScreenIntent()
        ) {
            try {
                startActivity(CallActivity.getStartIntent(this))
            } catch (_: Exception) {
                // seems like startActivity can throw AndroidRuntimeException and
                // ActivityNotFoundException, not yet sure when and why, lets show a notification
                callNotificationManager.setupNotification()
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callListener)
        val wasPrimaryCall = call == CallManager.getPrimaryCall()
        CallManager.onCallRemoved(call)
        if (CallManager.getPhoneState() == NoCall) {
            stopAutomaticSpeakerRouting()
        }
        if (CallManager.getPhoneState() == NoCall) {
            CallManager.inCallService = null
            callNotificationManager.cancelNotification()
        } else {
            callNotificationManager.setupNotification()
            if (wasPrimaryCall) {
                startActivity(CallActivity.getStartIntent(this))
            }
        }

        EventBus.getDefault().post(Events.RefreshCallLog)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) {
            currentAudioState = audioState
            CallManager.onAudioStateChanged(audioState)
        }
    }

    private fun startAutomaticSpeakerRouting() {
        if (!config.automaticSpeakerByProximity) {
            return
        }
        if (!config.disableProximitySensor && proximityWakeLock?.isHeld != true) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            proximityWakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "org.fossify.phone:service_wake_lock"
            )
            proximityWakeLock?.acquire(60 * 60 * 1000L)
        }
        proximitySensor?.let {
            sensorManager.registerListener(proximityListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopAutomaticSpeakerRouting() {
        sensorManager.unregisterListener(proximityListener)
        pendingRouteChange?.let(mainHandler::removeCallbacks)
        pendingRouteChange = null
        if (proximityWakeLock?.isHeld == true) {
            proximityWakeLock?.release()
        }
        proximityWakeLock = null
    }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!config.automaticSpeakerByProximity || CallManager.getState() != Call.STATE_ACTIVE) {
                return
            }
            val distance = event.values.firstOrNull() ?: return
            val maximumRange = proximitySensor?.maximumRange ?: return
            val isNear = distance == 0f || distance < maximumRange
            pendingRouteChange?.let(mainHandler::removeCallbacks)
            pendingRouteChange = Runnable {
                val route = currentAudioState?.route
                if (route == CallAudioState.ROUTE_BLUETOOTH || route == CallAudioState.ROUTE_WIRED_HEADSET) {
                    return@Runnable
                }
                CallManager.setSpeakerEnabled(!isNear)
            }
            mainHandler.postDelayed(pendingRouteChange!!, 300L)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onDestroy() {
        stopAutomaticSpeakerRouting()
        super.onDestroy()
        callNotificationManager.cancelNotification()
    }
}
