package com.hercules.mobile.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.core.content.edit
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.hercules.mobile.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ animated launch

/**
 * Picks up where the system splash ends (same mark, same black): plays the generated splash
 * animation once, then fades into the app.
 */
@Composable
fun LaunchArt(show: Boolean, durationMs: Long = 2_600) {
    var visible by remember { mutableStateOf(show) }
    LaunchedEffect(Unit) {
        delay(durationMs)
        visible = false
    }
    AnimatedVisibility(visible, enter = EnterTransition.None, exit = fadeOut(tween(500))) {
        Box(Modifier.fillMaxSize().background(Ink).clickable { visible = false }, contentAlignment = Alignment.Center) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        val d = runCatching {
                            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ctx.resources, R.raw.splash_anim))
                        }.getOrNull()
                        if (d is AnimatedImageDrawable) {
                            d.repeatCount = 0
                            setImageDrawable(d)
                            d.start()
                        } else {
                            setImageResource(R.drawable.splash_art)
                        }
                    }
                },
            )
        }
    }
}

// ------------------------------------------------------------------ onboarding

private class Step(val image: Int, val eyebrow: String, val title: String, val body: String)

private val Steps = listOf(
    Step(R.drawable.ill_score, "01 · The dial", "Know when your brain is ready", "A daily score from 1 to 100, built from your sleep, heart and how you feel."),
    Step(R.drawable.ill_no_watch, "02 · The watch", "Wear it to bed", "Install Hercules on your watch and open it once. It asks to read heart rate and sleep, only to score your nights. Your data stays on your devices."),
    Step(R.drawable.ill_sleep, "03 · Sleep", "Nothing to log", "Sleep is tracked for you. The first week, Hercules learns what normal looks like for you."),
    Step(R.drawable.ill_plan, "04 · The plan", "Aim for the big day", "Add an interview or pitch. Get a daily run-up and a clear go or wait call."),
    Step(R.drawable.ill_checkin, "05 · Reminders", "At most three a day", "Morning score, event prep and bedtime. Switch each off any time in Setup."),
)

private const val PREFS = "hercules_ui"
private const val KEY_ONBOARDED = "onboarded"

@Composable
fun rememberOnboarded(): Pair<Boolean, () -> Unit> {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }
    var done by remember { mutableStateOf(prefs.getBoolean(KEY_ONBOARDED, false)) }
    return done to { prefs.edit { putBoolean(KEY_ONBOARDED, true) }; done = true }
}

@Composable
fun Onboarding(onDone: () -> Unit) {
    val pager = rememberPagerState { Steps.size }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { onDone() }
    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("HERCULES", color = Text1, fontSize = 13.sp, letterSpacing = 5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("SKIP", letterSpacing = 1.5.sp, color = Text2, fontSize = 12.sp) }
        }
        HorizontalPager(pager, modifier = Modifier.weight(1f)) { page ->
            val s = Steps[page]
            val shown by animateFloatAsState(if (pager.currentPage == page) 1f else 0.3f, tween(400), label = "step")
            Column(Modifier.fillMaxSize().alpha(shown), verticalArrangement = Arrangement.Center) {
                Illustration(s.image, Modifier.fillMaxWidth().aspectRatio(1f))
                Eyebrow(s.eyebrow, color = Cyan)
                Text(s.title, fontSize = 30.sp, fontWeight = FontWeight.Light, color = Text1, lineHeight = 36.sp, modifier = Modifier.padding(top = 10.dp))
                Text(s.body, fontSize = 15.sp, color = Text2, lineHeight = 22.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Steps.indices.forEach { i ->
                    Box(Modifier.size(width = if (i == pager.currentPage) 28.dp else 10.dp, height = 3.dp).background(if (i <= pager.currentPage) Cyan else Hairline))
                }
            }
            val last = pager.currentPage == Steps.lastIndex
            if (last) TextButton(onClick = onDone) { Text("NOT NOW", letterSpacing = 1.5.sp, color = Text2, fontSize = 12.sp) }
            Button(
                onClick = {
                    when {
                        !last -> scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                        android.os.Build.VERSION.SDK_INT >= 33 && !com.hercules.mobile.notify.Notifier.canPost(ctx) -> askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        else -> onDone()
                    }
                },
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
            ) { Text(if (last) "ALLOW & START" else "NEXT", letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ------------------------------------------------------------------ navigation

/** Hairline-topped bar: the active tab gets a cyan rule and cyan icon. No pills. */
@Composable
fun <T> HerculesNavBar(items: List<T>, selected: T, icon: (T) -> ImageVector, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.background(Ink).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            items.forEach { t ->
                val on = t == selected
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable { onSelect(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(width = 28.dp, height = 2.dp).background(if (on) Cyan else Ink))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(icon(t), null, tint = if (on) Cyan else Text2, modifier = Modifier.size(22.dp))
                            Text(label(t).uppercase(UiLocale), fontSize = 10.sp, letterSpacing = 1.5.sp, color = if (on) Text1 else Text2, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
