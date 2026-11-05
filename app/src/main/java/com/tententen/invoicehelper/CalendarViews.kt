package com.tententen.invoicehelper

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Google Calendar's event colours (the "colorId" names you see in Google Calendar). */
object GColors {
    val byId = mapOf(
        "1" to Color(0xFF7986CB), // Lavender
        "2" to Color(0xFF33B679), // Sage
        "3" to Color(0xFF8E24AA), // Grape
        "4" to Color(0xFFE67C73), // Flamingo
        "5" to Color(0xFFF6BF26), // Banana
        "6" to Color(0xFFF4511E), // Tangerine
        "7" to Color(0xFF039BE5), // Peacock
        "8" to Color(0xFF616161), // Graphite
        "9" to Color(0xFF3F51B5), // Blueberry
        "10" to Color(0xFF0B8043), // Basil
        "11" to Color(0xFFD50000), // Tomato
    )
    val Banana = byId.getValue("5")
    val Graphite = byId.getValue("8")
    val Tomato = byId.getValue("11")

    /** Google's "today" blue. */
    @Composable
    fun today(): Color = if (isSystemInDarkTheme()) Color(0xFF8AB4F8) else Color(0xFF1A73E8)

    @Composable
    fun onToday(): Color = if (isSystemInDarkTheme()) Color(0xFF202124) else Color.White

    fun parse(hex: String?): Color? = hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)
        ?.let { Color(0xFF000000 or it) }

    /** Readable text on an event colour (dark text on Banana and other light colours). */
    fun textOn(c: Color): Color = if (c.luminance() > 0.5f) Color(0xDE000000) else Color.White
}

/** The colour Google Calendar shows this event in. */
fun CalEvent.color(calendarColor: Color): Color = when {
    colorId != null -> GColors.byId[colorId] ?: calendarColor
    onHold -> GColors.Banana
    unconfirmed -> GColors.Graphite
    else -> calendarColor
}

/** Short label for a month-grid chip. */
fun CalEvent.chipTitle(): String = when {
    onHold -> "On hold"
    unconfirmed -> "Unconfirmed"
    else -> title.removePrefix("🎧").removePrefix("CANCELLED ·").trim()
        .replace(Regex("""\s*\([^)]*\)$"""), "")
        .substringBefore(" | ").trim()
}

private val WEEK = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
private val MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
private val DOW = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

private fun today(): LocalDate = LocalDate.now(ZoneId.of(MEL_TZ))

/** Google Calendar style month view: grid lines, day numbers on top, coloured event chips in each day. */
@Composable
fun GoogleMonth(
    month: YearMonth,
    events: List<CalEvent>,
    calendarColor: Color,
    selected: LocalDate?,
    onDay: (LocalDate) -> Unit,
    onMonth: (YearMonth) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val byDate = remember(events) { events.groupBy { it.date } }
    val todayBlue = GColors.today()
    var drag by remember { mutableFloatStateOf(0f) }
    Column(
        Modifier.pointerInput(month) {
            // Swipe left/right to change month, like Google Calendar.
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (drag < -120) onMonth(month.plusMonths(1)) else if (drag > 120) onMonth(month.minusMonths(1))
                    drag = 0f
                },
                onDragCancel = { drag = 0f },
            ) { _, dx -> drag += dx }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(month.format(MONTH_TITLE), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { onMonth(YearMonth.from(today())) }) { Text("Today") }
            IconButton(onClick = { onMonth(month.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month")
            }
            IconButton(onClick = { onMonth(month.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            WEEK.forEach {
                Text(
                    it, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp,
                    fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = colors.outlineVariant)
        val first = month.atDay(1)
        val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
        val rows = (first.dayOfWeek.value - 1 + month.lengthOfMonth() + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.height(92.dp)) {
                for (c in 0 until 7) {
                    val date = start.plusDays((r * 7 + c).toLong())
                    if (c > 0) VerticalDivider(color = colors.outlineVariant)
                    DayCell(
                        date = date,
                        inMonth = date.month == month.month,
                        events = byDate[date].orEmpty().sortedWith(
                            compareBy<CalEvent>({ it.start == null }, { it.start }).thenBy { it.cancelled }
                        ),
                        calendarColor = calendarColor,
                        selected = date == selected,
                        todayBlue = todayBlue,
                        modifier = Modifier.weight(1f),
                        onClick = { onDay(date) },
                    )
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    events: List<CalEvent>,
    calendarColor: Color,
    selected: Boolean,
    todayBlue: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isToday = date == today()
    Column(
        modifier
            .height(92.dp)
            .background(if (selected) colors.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (isToday) todayBlue else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "${date.dayOfMonth}",
                fontSize = 12.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    isToday -> GColors.onToday()
                    !inMonth -> colors.onSurfaceVariant.copy(alpha = 0.5f)
                    else -> colors.onSurface
                },
            )
        }
        val shown = if (events.size > 3) events.take(2) else events
        shown.forEach { e -> Chip(e, calendarColor, faded = false) }
        if (events.size > shown.size) {
            Text("+${events.size - shown.size} more", fontSize = 10.sp, color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun Chip(e: CalEvent, calendarColor: Color, faded: Boolean) {
    val bg = e.color(calendarColor)
    val unconfirmedOutline = e.unconfirmed
    Box(
        Modifier
            .fillMaxWidth()
            .height(17.dp)
            .alpha(if (e.cancelled || faded) 0.5f else 1f)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            e.chipTitle(),
            fontSize = 10.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Medium,
            color = GColors.textOn(bg),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            textDecoration = if (e.cancelled) TextDecoration.LineThrough else null,
            fontStyle = if (unconfirmedOutline) androidx.compose.ui.text.font.FontStyle.Italic else null,
        )
    }
}

/** Google Calendar style "Schedule" list: day column on the left, coloured event cards on the right. */
@Composable
fun GoogleSchedule(
    events: List<CalEvent>,
    calendarColor: Color,
    onOpen: (CalEvent) -> Unit,
    timeLine: (CalEvent) -> String,
    whereLine: (CalEvent) -> String,
    title: (CalEvent) -> String,
) {
    val colors = MaterialTheme.colorScheme
    val todayBlue = GColors.today()
    var lastMonth: YearMonth? = null
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((date, dayEvents) in events.groupBy { it.date }.toSortedMap()) {
            val ym = YearMonth.from(date)
            if (ym != lastMonth) {
                if (lastMonth != null) {
                    Text(
                        date.format(MONTH_TITLE), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 60.dp, top = 6.dp),
                    )
                }
                lastMonth = ym
            }
            val isToday = date == today()
            Row {
                Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        date.format(DOW).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        color = if (isToday) todayBlue else colors.onSurfaceVariant,
                    )
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isToday) todayBlue else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${date.dayOfMonth}", fontSize = 20.sp,
                            color = if (isToday) GColors.onToday() else colors.onSurface,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    dayEvents.sortedWith(compareBy<CalEvent>({ it.start == null }, { it.start })).forEach { e ->
                        val bg = e.color(calendarColor)
                        val fg = GColors.textOn(bg)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .alpha(if (e.cancelled) 0.55f else 1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(bg)
                                .clickable { onOpen(e) }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            Text(
                                title(e), color = fg, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                textDecoration = if (e.cancelled) TextDecoration.LineThrough else null,
                            )
                            Text(timeLine(e), color = fg, fontSize = 13.sp)
                            whereLine(e).takeIf { it.isNotBlank() }?.let {
                                Text(it, color = fg.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Fri, Nov 27" style heading for a selected day. */
fun dayHeading(d: LocalDate): String = d.format(DOW) + ", " + d.format(MONTH_DAY)
