package com.hercules.shared

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.hercules.shared.db.HerculesDatabase

/** Used by the future iOS / watchOS apps (Swift calls this through the HerculesShared framework). */
fun createHerculesDatabase(name: String = "hercules.db"): HerculesDatabase =
    HerculesDatabase(NativeSqliteDriver(HerculesDatabase.Schema, name))
