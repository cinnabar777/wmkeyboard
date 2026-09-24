package com.wasimaster.wmkeyboard.app

import androidx.work.Configuration

/**
 * The full build's application: [WMApplication], plus WorkManager started on
 * demand instead of at every process start.
 *
 * Nothing in this app schedules work; WorkManager is here only because ML
 * Kit's ink recogniser downloads its models through it. Its androidx.startup
 * initializer still ran in every process, the keyboard's included, opening
 * WorkManager's database machinery before the first key could be drawn. The
 * full manifest removes that initializer, and this provider is what
 * `WorkManager.getInstance` falls back to instead, so the first ink model
 * download starts it and nothing before then pays for it.
 */
class WMFullApplication : WMApplication(), Configuration.Provider {
    override fun getWorkManagerConfiguration(): Configuration = Configuration.Builder().build()
}
