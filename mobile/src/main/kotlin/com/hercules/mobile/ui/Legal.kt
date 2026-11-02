package com.hercules.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The two legal pages, opened from Setup. Plain language first; every claim matches what the app actually does. */
enum class LegalDoc(val title: String, val updated: String, val intro: String, val sections: List<Pair<String, List<String>>>) {
    PRIVACY(
        "Privacy Policy",
        "3 November 2026",
        "Hercules turns what your watch measures into a daily readiness score. This page explains what data that involves, where it goes, and the control you have over it.",
        listOf(
            "The short version" to listOf(
                "Your data stays on your phone and your watch.",
                "Hercules has no account, no ads and no analytics, and it never sells or shares your data.",
                "Uninstalling the app deletes everything it stored.",
            ),
            "What Hercules collects" to listOf(
                "From your watch, with your permission: heart rate, sleep and wake times, steps, distance, floors, calories and motion. You can switch each sensor off in Setup.",
                "What you enter: check-ins (energy and stress), moments you plan, the steps you tick off, ratings after a moment, and sleep times you correct.",
                "What Hercules works out from these: your daily readiness score, focus windows, sleep advice and personal bests.",
            ),
            "How it is used" to listOf(
                "Only to calculate your score and advice, and to send you the reminders you have switched on. All of this runs on your devices.",
                "Hercules does not use your data for advertising, profiling or training any model.",
            ),
            "Where it is stored" to listOf(
                "In the app's private storage on your phone and watch. Phone and watch exchange data directly over Bluetooth or Wi-Fi using Google's Wearable Data Layer.",
                "Hercules does not upload anything to a server. If cloud sync is ever added, it will be off until you turn it on, and this policy will be updated first.",
                "App data is excluded from Android backups, so it does not leave your devices that way either.",
            ),
            "How long it is kept" to listOf(
                "Raw sensor readings are deleted after 30 days, and after 90 days at most.",
                "Daily summaries, scores, check-ins, moments and personal bests are kept on your device so you can see your history, until you delete the app or its data.",
            ),
            "Permissions" to listOf(
                "Body sensors, activity recognition and background sensor access (watch): to measure heart rate, sleep and movement.",
                "Notifications: for the reminders you choose. Each one can be turned off in Setup.",
            ),
            "Your control" to listOf(
                "Turn sensors and reminders on or off at any time in Setup.",
                "Delete a moment and its checklist from the moment's page.",
                "Delete all data with Android Settings > Apps > Hercules > Storage > Clear storage, on the phone and on the watch, or by uninstalling.",
            ),
            "Children" to listOf(
                "Hercules is not meant for children under 16 and does not knowingly collect their data.",
            ),
            "Changes" to listOf(
                "If this policy changes, the new version will appear here with a new date. Big changes will also be announced in the app.",
            ),
        ),
    ),
    TERMS(
        "Terms & Conditions",
        "3 November 2026",
        "These terms apply when you use the Hercules app on your phone and watch. By using the app you agree to them.",
        listOf(
            "Not medical advice" to listOf(
                "Hercules is a wellbeing tool, not a medical device. The readiness score and advice are estimates based on general research about sleep, recovery and focus, applied to your own data.",
                "Do not use Hercules to diagnose, treat or prevent any condition. If you are worried about your sleep, heart or health, talk to a doctor.",
                "Watch sensors can be wrong or miss data. The app does its best with what it receives, but cannot guarantee accuracy.",
            ),
            "Your decisions" to listOf(
                "Hercules may suggest moving a demanding task or moment to a better time. Whether and how you act on that is your decision.",
                "Hercules is not responsible for the outcome of interviews, exams, negotiations or any other moment.",
            ),
            "Using the app" to listOf(
                "You need a compatible Android phone and, for automatic tracking, a Wear OS watch.",
                "Use the app for your own personal, non-commercial purposes. Do not try to copy, reverse engineer or resell it.",
            ),
            "Your data" to listOf(
                "Your data stays yours. How it is handled is described in the Privacy Policy.",
            ),
            "Availability" to listOf(
                "The app is provided as it is. Features may change, and it may sometimes not work as expected. We aim to fix problems quickly but cannot promise uninterrupted service.",
            ),
            "Liability" to listOf(
                "To the extent the law allows, Hercules and its makers are not liable for indirect or consequential loss arising from use of the app. Nothing in these terms limits rights you have under consumer law.",
            ),
            "Changes" to listOf(
                "These terms may be updated. The new version will appear here with a new date. If you keep using the app after a change, you accept the new terms.",
            ),
        ),
    ),
}

@Composable
fun LegalScreen(doc: LegalDoc, padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = padding.calculateBottomPadding() + 32.dp),
    ) {
        item {
            Column {
                Text(doc.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light, color = Text1)
                VSpace(4.dp)
                Eyebrow("Last updated ${doc.updated}")
                VSpace(16.dp)
                Text(doc.intro, style = MaterialTheme.typography.bodyLarge, color = Text1)
                VSpace(8.dp)
            }
        }
        items(doc.sections, key = { it.first }) { (heading, paras) ->
            Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                Eyebrow(heading, color = Cyan)
                VSpace(8.dp)
                paras.forEach { p ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text("•", color = Text2, modifier = Modifier.padding(end = 10.dp))
                        Text(p, style = MaterialTheme.typography.bodyMedium, color = Text1)
                    }
                }
            }
        }
    }
}
