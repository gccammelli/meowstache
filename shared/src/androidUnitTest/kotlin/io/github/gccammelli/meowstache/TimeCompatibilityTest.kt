package io.github.gccammelli.meowstache

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.*

class TimeCompatibilityTest {
    @Test fun supportedNumberFormatsMatchJavaIncludingSignsGroupingAndPadding() {
        for ((pattern,value) in listOf("%d" to -12,"%06d" to -12,"%6d" to 12,"%,d" to -1234567,
            "%.0f" to 12.5,"%.2f" to 0.004,"%.2f" to -12.345,"%,.2f" to 12345.625,"%f" to 0.5)) {
            assertEquals(java.lang.String.format(java.util.Locale.UK,pattern,value),pattern.format(value),pattern)
        }
        assertEquals("null 50%","%s %d%%".format(null,50))
        assertEquals("0.00","%.2f".format(-0.004))
    }

    @Test fun supportedDateFormatsMatchJavaAtLeapDayAndAcrossTimeZones() {
        val date=LocalDate(2024,2,29); val time=LocalTime(7,5,9)
        val native=java.time.LocalDateTime.of(2024,2,29,7,5,9)
        for (pattern in listOf("d/M/yy","dd/MMMM/yyyy EEE","H:m:s","dd 'at' HH:mm","yyyy-MM-dd HH:mm:ss")) {
            val expected=native.format(java.time.format.DateTimeFormatter.ofPattern(pattern,java.util.Locale.UK))
            assertEquals(expected,DateTimeFormatter.ofPattern(pattern).format(date,time),pattern)
        }
        val zoned=LocalDateTime(2024,2,29,23,30).atZone(ZoneId.of("Europe/London"))
        assertEquals(23,zoned.hour); assertEquals(30,zoned.minute)
        assertEquals(kotlinx.datetime.DayOfWeek.THURSDAY,zoned.dayOfWeek)
        assertEquals("29/02/2024 23:30",zoned.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")))
        assertEquals(zoned.toInstant().toEpochMilli(),parseTs("2024-02-29T23:30:00Z"))
    }

    @Test fun calendarArithmeticMatchesJavaAcrossMonthAndYearBoundaries() {
        val date=LocalDate(2024,1,31)
        assertEquals(LocalDate(2024,2,29),date.plusMonths(1))
        assertEquals(LocalDate(2023,12,31),date.minusMonths(1))
        assertEquals(LocalDate(2024,2,1),date.plusDays(1L))
        assertEquals(LocalDate(2024,1,30),date.minusDays(1L))
        assertEquals(date,LocalDate.ofEpochDay(date.toEpochDay()))
        assertTrue(date.isAfter(LocalDate(2023,12,31)))
        assertFalse(date.isAfter(date))
        assertEquals(YearMonth(2023,12),YearMonth(2024,1).minusMonths(1))
        assertEquals(LocalDate(2024,2,29),YearMonth(2024,2).atEndOfMonth())
        val before=nowMs()
        val current=LocalTime.now(ZoneOffset.UTC)
        val after=nowMs()
        assertTrue(current.hour in 0..23)
        assertTrue(after>=before)
    }
}
