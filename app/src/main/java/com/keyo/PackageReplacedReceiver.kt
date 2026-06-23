package com.keyo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * After an APK update Android kills the keyboard process; it is started again lazily, often only
 * when an app requests the keyboard. That makes the first composition of the keyboard UI run
 * inside the show-IME window transaction, blocking this process's main thread while WindowManager
 * waits for the IME window to lay out. On slow devices the system's show retries can all expire,
 * and the requesting app is left believing the IME is visible — every later show request (even a
 * tap on the field) is then dropped as redundant, so the keyboard never appears in that app until
 * it restarts. Most visible in WhatsApp's auto-focused two-step-verification PIN prompt right
 * after a Keyo update.
 *
 * Receiving MY_PACKAGE_REPLACED starts the fresh process immediately after the update, and one
 * throwaway detached composition here loads and JITs the Compose runtime while nothing is waiting,
 * so the real first show later is fast. KeyoService.onCreate does the same for the boot case.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val t0 = android.os.SystemClock.elapsedRealtime()
        try {
            val recomposer = Recomposer(kotlin.coroutines.EmptyCoroutineContext)
            val warm = ComposeView(context.applicationContext).apply {
                setParentCompositionContext(recomposer)
                setContent {
                    // A small tree touching the libraries the keyboard layout uses (foundation
                    // layout/draw, material3 text, Canvas paths) — enough to load their statics.
                    Column(Modifier.fillMaxWidth().background(Color(0xFF1E1E2E))) {
                        Row(Modifier.fillMaxWidth()) {
                            Box(
                                Modifier.padding(2.dp)
                                    .background(Color(0xFF313244), RoundedCornerShape(6.dp))
                            ) { Text("warm", color = Color.White, fontSize = 18.sp) }
                        }
                        Canvas(Modifier.fillMaxWidth()) {
                            drawPath(Path().apply { moveTo(0f, 0f); lineTo(1f, 1f) }, Color.White)
                        }
                    }
                }
            }
            warm.measure(
                View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            warm.disposeComposition()
            recomposer.close()
            android.util.Log.i("Keyo", "post-update compose warmup in ${android.os.SystemClock.elapsedRealtime() - t0}ms")
        } catch (e: Throwable) {
            android.util.Log.w("Keyo", "post-update compose warmup skipped", e)
        }
    }
}
