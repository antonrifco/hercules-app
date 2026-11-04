package com.hercules.shared

import com.hercules.shared.app.HerculesCore

/**
 * Entry point for the iOS and watchOS apps (Swift: `AppleFactory.shared.core(deviceId:)`).
 * One database file per app; the watch and the phone each keep their own, like on Android.
 */
object AppleFactory {
    fun core(deviceId: String): HerculesCore = HerculesCore(createHerculesDatabase(), deviceId)
}
