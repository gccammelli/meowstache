package io.github.gccammelli.meowstache

import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/*
 * The java.time / String.format subset this app uses, on kotlinx-datetime, so the same code runs on iOS.
 * Only what the app calls is here; formatting is always English with "." decimals.
 */

typealias ZoneId = TimeZone
fun TimeZone.Companion.systemDefault(): TimeZone = currentSystemDefault()
object ZoneOffset { val UTC: TimeZone = TimeZone.UTC }
object Locale { val UK = Unit }

fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

fun LocalDate.Companion.now(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate = Clock.System.now().toLocalDateTime(zone).date
fun LocalDate.Companion.of(year: Int, month: Int, day: Int) = LocalDate(year, month, day)
fun LocalDate.Companion.ofEpochDay(day: Long) = fromEpochDays(day.toInt())
fun LocalDate.plusDays(n: Long) = plus(n.toInt(), DateTimeUnit.DAY)
fun LocalDate.plusDays(n: Int) = plus(n, DateTimeUnit.DAY)
fun LocalDate.minusDays(n: Long) = minus(n.toInt(), DateTimeUnit.DAY)
fun LocalDate.minusDays(n: Int) = minus(n, DateTimeUnit.DAY)
fun LocalDate.plusMonths(n: Long) = plus(n.toInt(), DateTimeUnit.MONTH)
fun LocalDate.minusMonths(n: Long) = minus(n.toInt(), DateTimeUnit.MONTH)
fun LocalDate.minusYears(n: Long) = minus(n.toInt(), DateTimeUnit.YEAR)
val LocalDate.monthValue get() = monthNumber
fun LocalDate.toEpochDay(): Long = toEpochDays().toLong()
fun LocalDate.isAfter(o: LocalDate) = this > o
fun LocalDate.atStartOfDay(zone: TimeZone) = ZonedDateTime(atStartOfDayIn(zone), zone)
fun LocalDate.format(f: DateTimeFormatter) = f.format(this, null)

fun LocalTime.Companion.now(zone: TimeZone = TimeZone.currentSystemDefault()): LocalTime = Clock.System.now().toLocalDateTime(zone).time

fun Instant.Companion.ofEpochMilli(ms: Long) = fromEpochMilliseconds(ms)
fun Instant.toEpochMilli() = toEpochMilliseconds()
fun Instant.atZone(zone: TimeZone) = ZonedDateTime(this, zone)
fun LocalDateTime.atZone(zone: TimeZone) = ZonedDateTime(toInstant(zone), zone)

/** "yyyy-MM-dd HH:mm:ss" in UTC, the timestamp format the activity API takes. */
fun utcStamp(ms: Long): String = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).let { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(it.date, it.time) }

/** An instant seen in a time zone (java.time.ZonedDateTime, reduced). */
class ZonedDateTime(private val instant: Instant, private val zone: TimeZone) {
    private val local: LocalDateTime = instant.toLocalDateTime(zone)
    fun toLocalDate(): LocalDate = local.date
    fun toLocalTime(): LocalTime = local.time
    fun toInstant() = instant
    val hour get() = local.hour
    val minute get() = local.minute
    val dayOfWeek get() = local.dayOfWeek
    fun format(f: DateTimeFormatter) = f.format(local.date, local.time)
}

/** A calendar month (java.time.YearMonth, reduced). */
data class YearMonth(val year: Int, val monthNumber: Int) : Comparable<YearMonth> {
    val month get() = Month(monthNumber)
    fun plusMonths(n: Long): YearMonth { val i = year * 12 + monthNumber - 1 + n.toInt(); return YearMonth(i / 12, i % 12 + 1) }
    fun minusMonths(n: Long) = plusMonths(-n)
    fun atDay(d: Int) = LocalDate(year, monthNumber, d)
    fun format(f: DateTimeFormatter) = f.format(atDay(1), null)
    fun atEndOfMonth(): LocalDate = atDay(1).plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
    override fun compareTo(other: YearMonth) = compareValuesBy(this, other, { it.year }, { it.monthNumber })
    companion object { fun from(d: LocalDate) = YearMonth(d.year, d.monthNumber) }
}

fun DayOfWeek.shortName() = name.take(3).lowercase().replaceFirstChar { it.uppercase() }
fun Month.shortName() = name.take(3).lowercase().replaceFirstChar { it.uppercase() }
fun Month.fullName() = name.lowercase().replaceFirstChar { it.uppercase() }

/** java.time.format.DateTimeFormatter for the patterns used here: d dd M MM MMM MMMM yy yyyy HH mm ss EEE and 'quoted' text. */
class DateTimeFormatter private constructor(private val pattern: String) {
    fun format(date: LocalDate?, time: LocalTime?): String {
        val out = StringBuilder(); var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'') { val end = pattern.indexOf('\'', i + 1).let { if (it < 0) pattern.length else it }; out.append(pattern, i + 1, end); i = end + 1; continue }
            var n = 1; while (i + n < pattern.length && pattern[i + n] == c) n++
            out.append(when (c) {
                'd' -> pad(date!!.dayOfMonth, n)
                'M' -> when (n) { 1, 2 -> pad(date!!.monthNumber, n); 3 -> date!!.month.shortName(); else -> date!!.month.fullName() }
                'y' -> if (n == 2) pad(date!!.year % 100, 2) else date!!.year.toString()
                'H' -> pad(time!!.hour, n)
                'm' -> pad(time!!.minute, n)
                's' -> pad(time!!.second, n)
                'E' -> date!!.dayOfWeek.shortName()
                else -> c.toString().repeat(n)
            })
            i += n
        }
        return out.toString()
    }
    private fun pad(v: Int, n: Int) = v.toString().padStart(n, '0')
    companion object {
        fun ofPattern(p: String) = DateTimeFormatter(p)
        fun ofPattern(p: String, @Suppress("UNUSED_PARAMETER") locale: Unit) = DateTimeFormatter(p)
    }
}

/**
 * String.format for the specifiers used here: %s %d %02d %,d %.Nf %%. Rounds half up like Java;
 * always "." decimals and "," grouping, suppressing a minus sign when a float rounds to zero.
 */
fun String.format(vararg args: Any?): String {
    val out = StringBuilder(); var arg = 0; var i = 0
    while (i < length) {
        val c = this[i]
        if (c != '%') { out.append(c); i++; continue }
        var j = i + 1
        while (j < length && this[j] !in "dsf%") j++
        val spec = substring(i + 1, j); val conv = this[j]; i = j + 1
        if (conv == '%') { out.append('%'); continue }
        val v = args[arg++]
        out.append(when (conv) {
            's' -> v.toString()
            'd' -> {
                val n = (v as Number).toLong()
                val digits = abs(n).toString().let { if (',' in spec) group(it) else it }
                val width = spec.filter { it.isDigit() }.toIntOrNull() ?: 0
                (if (n < 0) "-" else "") + digits.padStart((width - (if (n < 0) 1 else 0)).coerceAtLeast(0), if (spec.startsWith("0")) '0' else ' ')
            }
            else -> {
                val d = (v as Number).toDouble(); val dec = spec.substringAfter('.', "6").toInt()
                val scaled = floor(abs(d) * 10.0.pow(dec) + 0.5).toLong()
                val s = scaled.toString().padStart(dec + 1, '0')
                val int = s.dropLast(dec).let { if (',' in spec) group(it) else it }
                (if (d < 0 && scaled != 0L) "-" else "") + if (dec == 0) int else "$int.${s.takeLast(dec)}"
            }
        })
    }
    return out.toString()
}

private fun group(digits: String) = digits.reversed().chunked(3).joinToString(",").reversed()
