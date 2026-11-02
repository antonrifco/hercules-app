package com.hercules.shared

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.hercules.shared.db.HerculesDatabase

fun createHerculesDatabase(context: Context, name: String = "hercules.db"): HerculesDatabase =
    HerculesDatabase(AndroidSqliteDriver(HerculesDatabase.Schema, context.applicationContext, name))
