package com.keyo

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// ---- Hand-drawn vector key & toolbar icons ----
// One cohesive monochrome line style, no emoji. These are pure drawing functions: they take only a
// color/modifier (and a small variant flag) and render onto a Canvas, with no dependency on the
// keyboard's state — so they live here as top-level composables instead of inside KeyoService.

@Composable
internal fun MicGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val sw = h * 0.08f
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        val bodyW = w * 0.30f
        val left = (w - bodyW) / 2f
        // mic body (filled capsule)
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(left, h * 0.10f),
            size = androidx.compose.ui.geometry.Size(bodyW, h * 0.44f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(bodyW / 2f, bodyW / 2f)
        )
        // cradle arc
        drawArc(
            color = color, startAngle = 18f, sweepAngle = 144f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.24f, h * 0.30f),
            size = androidx.compose.ui.geometry.Size(w * 0.52f, h * 0.50f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = cap)
        )
        // stem + base
        drawLine(color, androidx.compose.ui.geometry.Offset(w / 2f, h * 0.80f), androidx.compose.ui.geometry.Offset(w / 2f, h * 0.90f), sw, cap)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.36f, h * 0.92f), androidx.compose.ui.geometry.Offset(w * 0.64f, h * 0.92f), sw, cap)
    }
}

// Modern "AI" sparkle (4-point star), filled.
@Composable
internal fun SparkleGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val rOut = size.minDimension / 2f * 0.96f
        val rIn = rOut * 0.34f
        val path = androidx.compose.ui.graphics.Path()
        val tips = floatArrayOf(-90f, 0f, 90f, 180f)
        for (i in tips.indices) {
            val a = Math.toRadians(tips[i].toDouble())
            val tx = cx + (kotlin.math.cos(a) * rOut).toFloat()
            val ty = cy + (kotlin.math.sin(a) * rOut).toFloat()
            val da = Math.toRadians((tips[i] + 45f).toDouble())
            val ix = cx + (kotlin.math.cos(da) * rIn).toFloat()
            val iy = cy + (kotlin.math.sin(da) * rIn).toFloat()
            if (i == 0) path.moveTo(tx, ty) else path.lineTo(tx, ty)
            path.lineTo(ix, iy)
        }
        path.close()
        drawPath(path, color)
    }
}

// ---- Toolbar vector icons (same monochrome line style) ----
@Composable
internal fun SmileyGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val cx = w / 2f; val cy = h / 2f
        val r = size.minDimension / 2f * 0.86f
        val sw = r * 0.16f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = sw)
        drawCircle(color, radius = r, center = androidx.compose.ui.geometry.Offset(cx, cy), style = stroke)
        drawCircle(color, radius = r * 0.12f, center = androidx.compose.ui.geometry.Offset(cx - r * 0.34f, cy - r * 0.22f))
        drawCircle(color, radius = r * 0.12f, center = androidx.compose.ui.geometry.Offset(cx + r * 0.34f, cy - r * 0.22f))
        drawArc(color, startAngle = 25f, sweepAngle = 130f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(cx - r * 0.5f, cy - r * 0.35f),
            size = androidx.compose.ui.geometry.Size(r, r * 0.8f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

@Composable
internal fun ClipboardGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val sw = h * 0.08f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.20f),
            size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.68f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f, w * 0.08f), style = stroke)
        drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.38f, h * 0.12f),
            size = androidx.compose.ui.geometry.Size(w * 0.24f, h * 0.14f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.04f, w * 0.04f))
    }
}

@Composable
internal fun StarGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val rOut = size.minDimension / 2f * 0.92f
        val rIn = rOut * 0.42f
        val path = androidx.compose.ui.graphics.Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) rOut else rIn
            val a = Math.toRadians((-90.0 + i * 36.0))
            val x = cx + (kotlin.math.cos(a) * r).toFloat()
            val y = cy + (kotlin.math.sin(a) * r).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color)
    }
}

@Composable
internal fun GearGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val r = size.minDimension / 2f
        val sw = r * 0.13f
        val teeth = 8
        val step = 360.0 / teeth
        val tw = step * 0.5          // angular width of each tooth
        val rOut = r * 0.94f
        val rIn = r * 0.66f
        fun pt(angDeg: Double, rad: Float) = androidx.compose.ui.geometry.Offset(
            cx + (kotlin.math.cos(Math.toRadians(angDeg)) * rad).toFloat(),
            cy + (kotlin.math.sin(Math.toRadians(angDeg)) * rad).toFloat()
        )
        val path = androidx.compose.ui.graphics.Path()
        for (i in 0 until teeth) {
            val c = i * step
            val corners = listOf(
                (c - tw / 2) to rIn, (c - tw / 2) to rOut,
                (c + tw / 2) to rOut, (c + tw / 2) to rIn
            )
            corners.forEachIndexed { idx, (ang, rad) ->
                val o = pt(ang, rad)
                if (i == 0 && idx == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
        }
        path.close()
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = sw, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(color, radius = r * 0.30f, center = androidx.compose.ui.geometry.Offset(cx, cy),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw))
    }
}

@Composable
internal fun SelectAllGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val sw = h * 0.08f
        val dashed = androidx.compose.ui.graphics.drawscope.Stroke(
            width = sw,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(w * 0.12f, w * 0.08f))
        )
        drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.16f, h * 0.16f),
            size = androidx.compose.ui.geometry.Size(w * 0.68f, h * 0.68f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f, w * 0.06f), style = dashed)
    }
}

// Circular undo/redo arrow (≈300° arc + a solid triangular arrowhead). mirror=true → redo.
@Composable
internal fun CurvedArrowGlyph(color: Color, mirror: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val cx = w / 2f; val cy = h / 2f
        val r = size.minDimension / 2f * 0.56f
        val sw = h * 0.10f
        fun rad(d: Double) = Math.toRadians(d)
        val startDeg = if (!mirror) 70.0 else 110.0
        val sweepDeg = if (!mirror) -300.0 else 300.0
        drawArc(color, startDeg.toFloat(), sweepDeg.toFloat(), false,
            topLeft = androidx.compose.ui.geometry.Offset(cx - r, cy - r),
            size = androidx.compose.ui.geometry.Size(2 * r, 2 * r),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        // Solid triangular arrowhead at the arc's end, aligned with the tangent
        val endDeg = startDeg + sweepDeg
        val ex = cx + (r * kotlin.math.cos(rad(endDeg))).toFloat()
        val ey = cy + (r * kotlin.math.sin(rad(endDeg))).toFloat()
        val tan = endDeg + (if (sweepDeg < 0) -90.0 else 90.0)
        val ah = r * 0.9f
        val tipX = ex + (kotlin.math.cos(rad(tan)) * ah * 0.5).toFloat()
        val tipY = ey + (kotlin.math.sin(rad(tan)) * ah * 0.5).toFloat()
        val bx = ex - (kotlin.math.cos(rad(tan)) * ah * 0.5).toFloat()
        val by = ey - (kotlin.math.sin(rad(tan)) * ah * 0.5).toFloat()
        val px = kotlin.math.cos(rad(tan + 90)).toFloat() * ah * 0.5f
        val py = kotlin.math.sin(rad(tan + 90)).toFloat() * ah * 0.5f
        val tri = androidx.compose.ui.graphics.Path().apply {
            moveTo(tipX, tipY)
            lineTo(bx + px, by + py)
            lineTo(bx - px, by - py)
            close()
        }
        drawPath(tri, color)
    }
}

@Composable
internal fun BackspaceGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val sw = h * 0.085f
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = sw, join = androidx.compose.ui.graphics.StrokeJoin.Round, cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
        fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * w, y * h)
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.07f, h * 0.5f)
            lineTo(w * 0.33f, h * 0.23f)
            lineTo(w * 0.93f, h * 0.23f)
            lineTo(w * 0.93f, h * 0.77f)
            lineTo(w * 0.33f, h * 0.77f)
            close()
        }
        drawPath(body, color, style = stroke)
        drawLine(color, o(0.50f, 0.40f), o(0.74f, 0.60f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, o(0.74f, 0.40f), o(0.50f, 0.60f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

// Enter / action glyph. kind: "return" | "search" | "send" | "next"
@Composable
internal fun EnterGlyph(kind: String, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val sw = h * 0.10f
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = sw, cap = cap, join = androidx.compose.ui.graphics.StrokeJoin.Round
        )
        fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * w, y * h)
        when (kind) {
            "search" -> {
                drawCircle(color, radius = w * 0.22f, center = o(0.44f, 0.44f), style = stroke)
                drawLine(color, o(0.60f, 0.60f), o(0.80f, 0.80f), sw, cap)
            }
            "send", "next" -> {
                drawLine(color, o(0.20f, 0.5f), o(0.78f, 0.5f), sw, cap)
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.60f, h * 0.32f); lineTo(w * 0.80f, h * 0.5f); lineTo(w * 0.60f, h * 0.68f)
                }
                drawPath(p, color, style = stroke)
            }
            else -> { // return arrow ⏎
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.78f, h * 0.28f); lineTo(w * 0.78f, h * 0.58f); lineTo(w * 0.28f, h * 0.58f)
                }
                drawPath(p, color, style = stroke)
                val head = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.42f, h * 0.46f); lineTo(w * 0.28f, h * 0.58f); lineTo(w * 0.42f, h * 0.70f)
                }
                drawPath(head, color, style = stroke)
            }
        }
    }
}
