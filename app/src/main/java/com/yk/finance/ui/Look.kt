package com.yk.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Atm
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yk.finance.domain.Looks

/**
 * Icon keys, as stored in the database, rendered.
 *
 * The database holds the key and nothing else, so an icon that is renamed or replaced
 * in a later version costs a line here rather than a migration. An unknown key falls
 * back to a tag rather than crashing: a backup restored onto an older build must still
 * open.
 */
private val ICONS: Map<String, ImageVector> = mapOf(
    "restaurant" to Icons.Default.Restaurant,
    "coffee" to Icons.Default.LocalCafe,
    "cart" to Icons.Default.ShoppingCart,
    "bus" to Icons.Default.DirectionsBus,
    "car" to Icons.Default.DirectionsCar,
    "fuel" to Icons.Default.LocalGasStation,
    "flight" to Icons.Default.Flight,
    "train" to Icons.Default.Train,
    "receipt" to Icons.Default.Receipt,
    "bolt" to Icons.Default.Bolt,
    "water" to Icons.Default.WaterDrop,
    "phone" to Icons.Default.Phone,
    "wifi" to Icons.Default.Wifi,
    "home" to Icons.Default.Home,
    "chair" to Icons.Default.Chair,
    "build" to Icons.Default.Build,
    "movie" to Icons.Default.Movie,
    "music" to Icons.Default.MusicNote,
    "sports" to Icons.Default.SportsSoccer,
    "gym" to Icons.Default.FitnessCenter,
    "game" to Icons.Default.SportsEsports,
    "book" to Icons.Default.Book,
    "school" to Icons.Default.School,
    "work" to Icons.Default.Work,
    "health" to Icons.Default.LocalHospital,
    "pill" to Icons.Default.Medication,
    "pets" to Icons.Default.Pets,
    "child" to Icons.Default.ChildCare,
    "gift" to Icons.Default.Redeem,
    "people" to Icons.Default.Groups,
    "person" to Icons.Default.Person,
    "handshake" to Icons.Default.Handshake,
    "savings" to Icons.Default.Savings,
    "card" to Icons.Default.CreditCard,
    "cash" to Icons.Default.Payments,
    "wallet" to Icons.Default.AccountBalanceWallet,
    "bank" to Icons.Default.AccountBalance,
    "atm" to Icons.Default.Atm,
    "trending" to Icons.Default.TrendingUp,
    "tag" to Icons.Default.Sell,
)

fun iconFor(key: String?): ImageVector = ICONS[key] ?: Icons.Default.Sell

/** "#RRGGBB" -> Color. Anything unparseable becomes grey rather than throwing. */
fun colourOf(hex: String?): Color {
    val cleaned = hex?.trim()?.removePrefix("#") ?: return Color(0xFF78909C)
    if (cleaned.length != 6) return Color(0xFF78909C)
    val value = cleaned.toLongOrNull(16) ?: return Color(0xFF78909C)
    return Color(0xFF000000 or value)
}

/** The coloured disc every category and account is recognised by. */
@Composable
fun IconBubble(
    iconKey: String?,
    colourHex: String?,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    Box(
        modifier
            .size(size)
            .background(colourOf(colourHex), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            iconFor(iconKey),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** For a row whose category is not set yet - grey, and clearly a gap rather than a choice. */
@Composable
fun UncategorisedBubble(modifier: Modifier = Modifier, size: Dp = 44.dp) =
    IconBubble("tag", Looks.colourFromName("__uncategorised__"), modifier, size)
