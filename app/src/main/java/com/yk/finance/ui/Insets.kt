package com.yk.finance.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable

/**
 * The strip along the top of the window that no content may sit under.
 *
 * [WindowInsets.statusBars] alone is not it, and believing otherwise was the notch bug.
 * The theme hides the status bar, and a hidden bar reports a zero inset - so on a phone
 * with a cutout every header slid up into it and the title was clipped by the camera.
 * The cutout is reported separately and keeps being reported whether the bar is on
 * screen or not, which is what makes this right in both states.
 *
 * [union] rather than addition: on a phone that draws its status bar across the cutout
 * the two describe the same strip, and adding them would leave a double-height gap
 * above every header.
 *
 * Restricted to the top edge because a side cutout in landscape is the business of
 * whatever is drawn at that edge, not of a header that only occupies the top.
 *
 * There is one definition of this because there was one bug repeated across six
 * screens, and a seventh added later should not have to rediscover it. Apply it to the
 * outermost bar of every full-screen destination, after the background so the bar's
 * colour still reaches the physical top edge:
 *
 *     Modifier.background(money.header).windowInsetsPadding(topBarInset).padding(8.dp)
 */
val topBarInset: WindowInsets
    // Not @ReadOnlyComposable, however much it looks like a plain read: statusBars and
    // displayCutout each register an insets holder with the composition so the value
    // recomposes when the bar or the cutout changes, and the compiler rejects that in a
    // read-only composable.
    @Composable
    get() = WindowInsets.statusBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top)
