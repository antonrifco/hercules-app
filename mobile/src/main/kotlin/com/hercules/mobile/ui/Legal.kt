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

/** About Hercules and the two legal pages, opened from Setup. Plain language first; every claim matches what the app does. */
enum class LegalDoc(val title: String, val updated: String, val intro: String, val sections: List<Pair<String, List<String>>>) {
    ABOUT(
        "About Hercules",
        "20 November 2026",
        "Hercules estimates how alert you are right now, on a scale from 1 to 100, from your sleep, your body signals and a short reaction test. It helps you put your hardest thinking where you're sharpest and prepare for the Moments that matter.",
        listOf(
            "What it does" to listOf(
                "Home shows your score, one of five bands (Prime, Good, Fair, Low, Drained), how much to trust the number, and one next step.",
                "The Reaction Test (3 minutes) measures how quickly you respond each time Herc appears. It's the one direct measure of how you're doing, so a recent test weighs heavily in your score.",
                "Over time Hercules learns the part of the day you tend to perform best, and tunes the score to you from your own tests.",
                "Moments: add an interview, exam or pitch up to 7 days ahead, and Hercules suggests how to prepare your sleep and recovery for it.",
                "The watch app shows the same score, runs the same test and measures your pulse, sleep and steps.",
            ),
            "How the score is made" to listOf(
                "Sleep pressure: it builds while you're awake and clears while you sleep, so short nights add up into sleep debt.",
                "Body clock: alertness rises and dips over 24 hours, best in the afternoon and lowest in the early hours. Hercules moves this to fit when you usually wake, and later to fit your own tests.",
                "Recovery: HRV, resting heart rate and skin temperature are compared with your own last 10 days. Readings worse than your usual lower the score; better ones never push it up.",
                "Reaction Test: a test from the last 2 hours counts for 60% of the score.",
                "Confidence (0–100%) shows how much data is behind the number. Missing data is never counted as zero.",
            ),
            "The research behind it" to listOf(
                "The sleep model is McCauley et al. 2013 (Sleep), a biomathematical model built to predict lapses of attention on the Psychomotor Vigilance Test after normal sleep, total sleep loss, chronic short sleep, night shifts and naps. Hercules uses the published values from the paper. It extends the two-process model of sleep regulation (Borbély 1982; reappraised 2016).",
                "Sleep-restriction experiments (Van Dongen et al. 2003, Sleep) showed that lapses in attention build up night after night of short sleep, even when people feel used to it. People also differ consistently in how much sleep loss affects them (Van Dongen et al. 2004), which is why Hercules tunes the score to you.",
                "Unlike simpler models, it has a slow second process, so a week of short nights keeps adding up instead of levelling off, and the body clock's effect grows with time awake. Its predicted lapses are turned into a 1–100 scale by Hercules.",
                "The Reaction Test follows the Psychomotor Vigilance Test (PVT), the standard measure of how tiredness slows attention (Basner & Dinges 2011), in its brief 3-minute form.",
                "Heart rate variability and resting heart rate track recovery from stress and strain (Plews et al. 2013; Buchheit 2014). Evidence that wearables predict thinking performance is mixed, so these signals only adjust the score a little until your own tests show how much they matter for you.",
                "Skin temperature and breathing rate above your usual can be early signs of illness (Natarajan et al. 2020; Smarr et al. 2020). Low blood glucose impairs thinking (International Hypoglycaemia Study Group 2017).",
            ),
            "Limits" to listOf(
                "Hercules is a wellness estimate, not a medical device or a safety assessment. Never use it to decide whether you can drive or operate machinery.",
                "It doesn't know about caffeine, alcohol, medication or illness, except as far as they show up in your body signals or your Reaction Test.",
                "The bands and settings are product choices based on the research, not clinically validated thresholds.",
            ),
            "Privacy" to listOf(
                "Everything stays on your phone and watch. No account, no server, no ads. Hercules keeps 10 days of data; see the Privacy Policy.",
            ),
            "Herc" to listOf(
                "Herc the lion is your guide. His face follows your band, from fierce and focused at Prime to worn down at Drained.",
            ),
        ),
    ),
    PRIVACY(
        "Privacy Policy",
        "20 November 2026",
        "Hercules turns what your devices measure into a cognitive score for right now. This page explains what data that involves, where it goes, and the control you have over it.",
        listOf(
            "The short version" to listOf(
                "Your data stays on your phone and your watch.",
                "Hercules has no account, no ads and no analytics, and it never sells or shares your data.",
                "Uninstalling the app deletes everything it stored.",
            ),
            "What Hercules collects" to listOf(
                "From Health Connect, only the types you allow: sleep sessions, heart rate variability, resting heart rate, steps and exercise sessions. Optionally respiratory rate, skin temperature, blood oxygen, blood glucose and heart rate. Hercules also reads which kind of device recorded each value (for example a phone or a watch), so it never compares a phone reading with a watch's. Hercules only reads; it never writes to Health Connect.",
                "From your phone's ambient light sensor: light levels, sampled while the app is open and occasionally in the background. No camera, no location.",
                "From your watch, with your permission: heart rate, sleep and wake times, steps, distance, floors, calories and motion. You can switch each sensor off in Setup.",
                "From the Reaction Test: how fast you tapped each time Herc appeared.",
                "Optional, only if you allow Usage Access: the times your phone's screen turned off and was unlocked, to estimate your sleep without a watch. Hercules never reads which apps you use.",
                "What you enter: moments you plan, the steps you tick off, ratings after a moment, sleep times you correct, and your score settings.",
                "What Hercules works out from these: your score with its confidence, predicted alertness from your sleep and body clock, a recovery adjustment from your body signals, personal baselines, your peak cognitive window, preparation status and personal bests.",
                "Personal tuning: from 20 Reaction Tests on, Hercules fits a few numbers to you (your body-clock peak, its strength, and how much HRV, resting heart rate and skin temperature matter for you). Only these numbers and a count of tests are kept, not the data they came from. The phone shares them with your watch so both score the same way.",
            ),
            "How it is used" to listOf(
                "Only to calculate your score, its confidence and your advice, to tune the score to you, and to send you the reminders you have switched on. All of this runs on your devices.",
                "Hercules does not use your data for advertising, profiling or training any model.",
            ),
            "Where it is stored" to listOf(
                "In the app's private storage on your phone and watch. Phone and watch exchange data directly over Bluetooth or Wi-Fi using Google's Wearable Data Layer.",
                "Hercules does not upload anything to a server. If cloud sync is ever added, it will be off until you turn it on, and this policy will be updated first.",
                "App data is excluded from Android backups, so it does not leave your devices that way either.",
            ),
            "How long it is kept" to listOf(
                "Sensor readings, sleep, Reaction Tests, daily summaries and scores are kept for 10 days, then deleted automatically, day by day. Health Connect keeps its own copy under its own settings; Hercules reads only the last 10 days of it.",
                "Your personal tuning numbers and score settings are kept until you reset them, clear the app's data or uninstall.",
                "Moments, their preparation steps and ratings are kept until you delete them, clear the app's data or uninstall.",
            ),
            "Permissions" to listOf(
                "Health Connect (read only): the record types listed above. You can grant or withdraw each one in Setup or in Health Connect.",
                "Body sensors, activity recognition and background sensor access (watch): to measure heart rate, sleep and movement.",
                "Hercules has no internet permission, so it can't send your data anywhere.",
                "Notifications: for the reminders you choose. Each one can be turned off in Setup.",
                "Usage Access (optional, phone): screen-off and unlock times only, for the sleep estimate. Turn it on or off in Setup > Sensors & data or in Android settings.",
            ),
            "Your control" to listOf(
                "Turn sensors and reminders on or off at any time in Setup.",
                "Cancel a moment from its page.",
                "Delete all data in Setup > Data, with Android Settings > Apps > Hercules > Storage > Clear storage, or by uninstalling.",
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
        "20 November 2026",
        "These terms apply when you use the Hercules app on your phone and watch. By using the app you agree to them.",
        listOf(
            "Not medical advice" to listOf(
                "Hercules is a wellbeing tool, not a medical device. The score, its confidence, the peak window and advice are wellness estimates based on general research about sleep, the body clock, recovery and reaction time, applied to your own data.",
                "The score is not a medical or safety assessment. Never use it to decide whether you are fit to drive, fly, operate machinery or do anything else where a mistake could hurt you or others.",
                "The score does not account for caffeine, alcohol, medication or illness, except as far as they show up in your body signals or your Reaction Test.",
                "Do not use Hercules to diagnose, treat or prevent any condition. If you are worried about your sleep, heart or health, talk to a doctor.",
                "Watch and phone sensors can be wrong or miss data. Without sleep from a watch, or with only a Reaction Test, the score rests on less and says so with a lower confidence. The app does its best with what it receives, but cannot guarantee accuracy.",
            ),
            "Your decisions" to listOf(
                "Hercules suggests how to prepare and when you tend to perform best. Whether and how you act on that is your decision.",
                "Hercules is not responsible for the outcome of interviews, exams, negotiations or any other moment.",
            ),
            "Using the app" to listOf(
                "You need a compatible Android phone with Health Connect. A Wear OS watch is optional.",
                "Use the app for your own personal, non-commercial purposes. Do not try to copy, reverse engineer or resell it.",
            ),
            "Your data" to listOf(
                "Your data stays yours and stays on your devices. Hercules keeps 10 days of it; older data is deleted automatically and can't be recovered. How it is handled is described in the Privacy Policy.",
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
