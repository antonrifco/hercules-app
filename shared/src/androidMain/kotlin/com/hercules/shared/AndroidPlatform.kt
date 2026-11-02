package com.hercules.shared

import android.content.Context
import java.util.UUID

/** Watch ↔ phone protocol over the Wearable Data Layer. Both apps share applicationId com.hercules.app. */
object WearProtocol {
    /** Declared by the phone app in res/values/wear.xml. */
    const val PHONE_CAPABILITY = "hercules_phone"
    const val SYNC_PATH = "/hercules/sync/v1"
    /** DataClient item the phone writes with the latest readiness + next event (key [STATE_KEY], JSON). */
    const val STATE_PATH = "/hercules/state"
    const val STATE_KEY = "json"
    /** Sensor on/off choice ([com.hercules.shared.sensors.SensorConfig] JSON), written by whichever side changed it. */
    const val SENSOR_CONFIG_PATH = "/hercules/sensors/config"
    /** Written by the watch: supported + active sensors ([com.hercules.shared.sensors.SensorReport] JSON). */
    const val SENSOR_REPORT_PATH = "/hercules/sensors/report"
    /** A check-in made on the watch ([com.hercules.shared.cognitive.CheckIn] JSON), picked up by the phone. */
    const val CHECKIN_PATH = "/hercules/checkin"
}

object DeviceId {
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences("hercules", Context.MODE_PRIVATE)
        return prefs.getString("device_id", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
    }
}
