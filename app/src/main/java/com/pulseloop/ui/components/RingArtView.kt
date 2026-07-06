package com.pulseloop.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.pulseloop.R

/**
 * Ported from RingArtView.swift (iOS #48).
 * Renders a smart ring's product art on a light circular "platter" for the pairing carousel
 * and the Settings hero: a soft tinted glow behind a light gradient disc so black / gold /
 * rose-gold rings all read on the dark theme.
 */
@Composable
fun RingArtView(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Float = 180f,
    @DrawableRes imageRes: Int? = null,
) {
    // Tint follows the selected model with a short cross-fade (iOS animates .easeInOut 0.25).
    val animatedTint by animateColorAsState(tint, tween(250), label = "ringArtTint")

    Box(
        modifier = modifier.size(size.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Soft themed glow so the platter sits in the app's color world.
        Box(
            Modifier
                .size(size.dp)
                .blur((size * 0.13f).dp, BlurredEdgeTreatment.Unbounded)
                .clip(CircleShape)
                .background(animatedTint.copy(alpha = 0.22f)),
        )

        // Light platter: vertical white→light-gray gradient, subtle border, soft drop shadow.
        Box(
            Modifier
                .size(size.dp)
                .shadow((size * 0.05f).dp, CircleShape)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFE0E0E0))))
                .border(1.dp, Color.Black.copy(alpha = 0.06f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(imageRes ?: FALLBACK_IMAGE_RES),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding((size * 0.08f).dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** Generic Colmi-style ring shown when no model-specific image is available. */
private val FALLBACK_IMAGE_RES = R.drawable.ring_colmi_r09
