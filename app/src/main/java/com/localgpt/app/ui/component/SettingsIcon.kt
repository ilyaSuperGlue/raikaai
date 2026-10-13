package com.localgpt.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Standard leading icon for a settings row. */
@Composable
fun SettingsIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(24.dp),
    )
}

/**
 * Reusable squircle icon container adapted from KZKT's design system:
 * 40x40dp box, 12dp rounded corner, primary tonal background with primary icon tint.
 */
@Composable
fun SquircleIconBox(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    boxSize: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    shapeRadius: Dp = 12.dp,
    containerColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
    contentColor: Color = MaterialTheme.colorScheme.primary,
    showBadge: Boolean = false,
    badgeColor: Color = MaterialTheme.colorScheme.error,
) {
    Box(
        modifier =
            modifier
                .size(boxSize)
                .clip(RoundedCornerShape(shapeRadius))
                .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        if (showBadge) {
            BadgedBox(
                badge = {
                    Badge(containerColor = badgeColor)
                },
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(iconSize),
                )
            }
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * SquircleIconBox supporting Painter icons.
 */
@Composable
fun SquircleIconBox(
    painter: Painter,
    modifier: Modifier = Modifier,
    boxSize: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    shapeRadius: Dp = 12.dp,
    containerColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
    contentColor: Color = MaterialTheme.colorScheme.primary,
    showBadge: Boolean = false,
    badgeColor: Color = MaterialTheme.colorScheme.error,
) {
    Box(
        modifier =
            modifier
                .size(boxSize)
                .clip(RoundedCornerShape(shapeRadius))
                .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        if (showBadge) {
            BadgedBox(
                badge = {
                    Badge(containerColor = badgeColor)
                },
            ) {
                Icon(
                    painter = painter,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(iconSize),
                )
            }
        } else {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
