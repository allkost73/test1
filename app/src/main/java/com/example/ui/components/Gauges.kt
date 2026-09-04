package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DarkBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceElevated
import com.example.ui.theme.GaugeGreen
import com.example.ui.theme.GaugeRed
import com.example.ui.theme.GaugeYellow
import com.example.ui.theme.SitrakOrange
import com.example.ui.theme.TelemetryCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CircularDialGauge(
    value: Float,
    minValue: Float = 0f,
    maxValue: Float = 2500f,
    title: String,
    unit: String,
    modifier: Modifier = Modifier,
    activeColor: Color = SitrakOrange,
    greenZoneStart: Float? = null,
    greenZoneEnd: Float? = null,
    redZoneStart: Float? = null,
    testTag: String = "circular_dial_gauge"
) {
    val animatedVal by animateFloatAsState(
        targetValue = value.coerceIn(minValue, maxValue),
        animationSpec = tween(250),
        label = "dial_anim"
    )

    val progress = ((animatedVal - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
    val startAngle = 140f
    val sweepAngle = 260f

    Box(
        modifier = modifier
            .testTag(testTag)
            .background(DarkSurfaceElevated, RoundedCornerShape(16.dp))
            .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(130.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(120.dp)) {
                    val strokeWidth = 10.dp.toPx()
                    val radius = (size.minDimension - strokeWidth) / 2
                    val center = Offset(size.width / 2, size.height / 2)

                    // Background Track
                    drawArc(
                        color = Color(0xFF262C36),
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )

                    // Optional Green economy zone for truck RPM
                    if (greenZoneStart != null && greenZoneEnd != null) {
                        val gStartNorm = ((greenZoneStart - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
                        val gEndNorm = ((greenZoneEnd - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
                        drawArc(
                            color = GaugeGreen.copy(alpha = 0.35f),
                            startAngle = startAngle + (gStartNorm * sweepAngle),
                            sweepAngle = (gEndNorm - gStartNorm) * sweepAngle,
                            useCenter = false,
                            topLeft = Offset(center.x - radius, center.y - radius),
                            size = Size(radius * 2, radius * 2),
                            style = Stroke(width = strokeWidth + 2f, cap = StrokeCap.Butt)
                        )
                    }

                    // Optional Redline zone
                    if (redZoneStart != null) {
                        val rStartNorm = ((redZoneStart - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
                        drawArc(
                            color = GaugeRed.copy(alpha = 0.45f),
                            startAngle = startAngle + (rStartNorm * sweepAngle),
                            sweepAngle = (1f - rStartNorm) * sweepAngle,
                            useCenter = false,
                            topLeft = Offset(center.x - radius, center.y - radius),
                            size = Size(radius * 2, radius * 2),
                            style = Stroke(width = strokeWidth + 2f, cap = StrokeCap.Round)
                        )
                    }

                    // Active Sweep Arc
                    if (progress > 0.005f) {
                        drawArc(
                            brush = Brush.sweepGradient(
                                0.0f to activeColor.copy(alpha = 0.7f),
                                0.7f to activeColor,
                                1.0f to activeColor
                            ),
                            startAngle = startAngle,
                            sweepAngle = progress * sweepAngle,
                            useCenter = false,
                            topLeft = Offset(center.x - radius, center.y - radius),
                            size = Size(radius * 2, radius * 2),
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                    }

                    // Small indicator tick marks
                    val tickAngle = startAngle + (progress * sweepAngle)
                    val rad = Math.toRadians(tickAngle.toDouble())
                    val tipX = center.x + ((radius - strokeWidth / 2) * cos(rad)).toFloat()
                    val tipY = center.y + ((radius - strokeWidth / 2) * sin(rad)).toFloat()
                    drawCircle(
                        color = Color.White,
                        radius = 4.dp.toPx(),
                        center = Offset(tipX, tipY)
                    )
                }

                // Center Value Readout
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (maxValue > 500) animatedVal.toInt().toString() else String.format("%.1f", animatedVal),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 22.sp
                        ),
                        color = TextPrimary
                    )
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary
            )
        }
    }
}

@Composable
fun LinearBarGauge(
    title: String,
    value: Float,
    unit: String,
    minValue: Float,
    maxValue: Float,
    modifier: Modifier = Modifier,
    activeColor: Color = TelemetryCyan,
    warningThreshold: Float? = null,
    dangerThreshold: Float? = null,
    testTag: String = "linear_bar_gauge"
) {
    val animatedProgress by animateFloatAsState(
        targetValue = ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f),
        animationSpec = tween(200),
        label = "bar_anim"
    )

    val color = when {
        dangerThreshold != null && value >= dangerThreshold -> GaugeRed
        warningThreshold != null && value >= warningThreshold -> GaugeYellow
        else -> activeColor
    }

    Column(
        modifier = modifier
            .testTag(testTag)
            .background(DarkSurfaceElevated, RoundedCornerShape(12.dp))
            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = TextSecondary
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (maxValue > 200) value.toInt().toString() else String.format("%.1f", value),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = color
                )
                Text(
                    text = " $unit",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(Color(0xFF262C36), RoundedCornerShape(4.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .height(8.dp)
                    .background(
                        Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color)),
                        RoundedCornerShape(4.dp)
                    )
            )
        }
    }
}
