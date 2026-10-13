package io.github.gccammelli.meowstache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class HistoryTest {
    private val z = ZoneOffset.UTC
    private fun t(s: String) = LocalDateTime.parse(s).toInstant(z).toEpochMilli()
    private val fmt: (Double) -> String = { "%.1f".format(it) }

    @Test fun weekEndsYesterday() {
        val b = buckets(Range.WEEK, LocalDate.of(2026, 10, 13), null)
        assertEquals(7, b.size)
        assertEquals(LocalDate.of(2026, 10, 6), b.first().start)
        assertEquals(LocalDate.of(2026, 10, 12), b.last().end)
        assertEquals("6 October – 12 October", rangeTitle(b))
    }

    @Test fun allCoversEveryMonth() {
        val b = buckets(Range.ALL, LocalDate.of(2026, 10, 13), LocalDate.of(2024, 8, 3))
        assertEquals(27, b.size)
        assertEquals(LocalDate.of(2024, 8, 1), b.first().start)
    }

    @Test fun seriesAverageAndCount() {
        val r = listOf(Reading(t("2026-10-12T05:02:00"), 10.0), Reading(t("2026-10-12T23:05:00"), 11.0))
        val b = buckets(Range.WEEK, LocalDate.of(2026, 10, 13), null)
        assertEquals(10.5, weightSeries(r, b, z).last()!!, 1e-9)
        assertEquals(null, weightSeries(r, b, z).first())
        assertEquals(2, countSeries(r.map { it.ts }, b, z).last())
    }

    @Test fun durationFromDetectToLeaving() {
        val w = listOf(Reading(t("2026-10-12T23:05:00"), 10.8))
        val acts = listOf(
            Act(t("2026-10-12T23:02:00"), "robotStatusCatDetect"),
            Act(t("2026-10-12T23:14:00"), "robotCycleStatusDump"),
        )
        // dump 23:26 minus 8 min delay = left 23:18; detected 23:14 (+9 s detect lag) → 4.15 min
        assertEquals(4.15, visitDurations(w, acts, 8)[w[0].ts]!!, 1e-9)
    }

    @Test fun noVisitAndTooMany() {
        val now = t("2026-10-13T12:00:00")
        val s = AlertSettings(noVisitEnabled = true, noVisitDays = 2, tooManyEnabled = true, tooManyVisits = 2)
        val old = mapOf("a" to listOf(Reading(t("2026-10-11T11:00:00"), 10.0)))
        assertTrue(evaluateAlerts(now, listOf("a" to "Luna"), old, s, fmt, z).any { it.key.startsWith("novisit") })
        val busy = mapOf("a" to (1..3).map { Reading(now - it * 3_600_000L, 10.0) })
        val alerts = evaluateAlerts(now, listOf("a" to "Luna"), busy, s, fmt, z)
        assertTrue(alerts.any { it.key.startsWith("toomany") })
        assertTrue(alerts.none { it.key.startsWith("novisit") })
    }

    @Test fun targetCrossingBothWays() {
        val now = t("2026-10-13T12:00:00")
        val r = listOf(
            Reading(t("2026-10-10T08:00:00"), 10.6), Reading(t("2026-10-11T08:00:00"), 11.1), // up through 11.0
            Reading(t("2026-10-12T08:00:00"), 10.9), // back down
        )
        val s = AlertSettings(targets = mapOf("a" to Target(11.0, t("2026-10-01T00:00:00"))))
        val a = evaluateAlerts(now, listOf("a" to "Mochi"), mapOf("a" to r), s, fmt, z)
        assertEquals(2, a.size)
        assertTrue(a[0].body.endsWith("from below.")); assertTrue(a[1].body.endsWith("from above."))
        // Crossings before the target was set are ignored.
        val late = s.copy(targets = mapOf("a" to Target(11.0, t("2026-10-12T00:00:00"))))
        assertEquals(1, evaluateAlerts(now, listOf("a" to "Mochi"), mapOf("a" to r), late, fmt, z).size)
    }

    @Test fun parsesApiTimestamps() {
        assertEquals(t("2026-10-12T23:05:00"), parseTs("2026-10-12T23:05:00Z"))
        assertEquals(t("2026-10-12T23:05:00"), parseTs("2026-10-12 23:05:00"))
        assertEquals(t("2026-10-12T23:05:00"), parseTs("2026-10-12T23:05:00.000000"))
    }

    @Test fun recoversOlderWeighingsFromActivity() {
        val known = mapOf(
            "mochi" to listOf(Reading(t("2026-10-12T23:05:00"), 10.9)),
            "luna" to listOf(Reading(t("2026-10-12T08:15:00"), 8.0)),
        )
        val acts = listOf(
            Act(t("2026-10-12T23:05:30"), "catWeight", "10.9"), // same weighing as the API reading: skipped
            Act(t("2025-09-12T06:50:00"), "catWeight", "8.5"),
            Act(t("2025-09-12T06:40:00"), "catWeight", "11.5"),
            Act(t("2025-09-12T06:30:00"), "robotCycleStatusIdle"),
        )
        val h = attributeWeights(known, acts)
        assertEquals(listOf(11.5, 10.9), h.getValue("mochi").map { it.lbs })
        assertEquals(listOf(8.5, 8.0), h.getValue("luna").map { it.lbs })
    }

    private fun steadyMonth(perDay: Int, lbs: Double = 10.0) = (1..28).flatMap { d ->
        (0 until perDay).map { k -> Reading(t("2026-09-%02dT%02d:00:00".format(d, 6 + k * 6)), lbs) }
    }

    @Test fun patternFlagsVisitSpike() {
        val day = LocalDate.of(2026, 9, 29)
        val spike = (0 until 8).map { Reading(t("2026-09-29T%02d:00:00".format(2 + it * 2)), 10.0) }
        val p = detectPatterns(steadyMonth(2) + spike, emptyMap(), day, z)
        val v = p.single { it.metric == Metric.VISITS }
        assertTrue(v.up && v.significant)
        assertEquals("Visits: Increased 4X from 2 to 8", v.text(fmt))
        // a normal day raises nothing
        val normal = (0 until 2).map { Reading(t("2026-09-29T%02d:00:00".format(6 + it * 6)), 10.0) }
        assertTrue(detectPatterns(steadyMonth(2) + normal, emptyMap(), day, z).isEmpty())
    }

    @Test fun patternFlagsWeightDrop() {
        val day = LocalDate.of(2026, 9, 29)
        val r = steadyMonth(2) + Reading(t("2026-09-29T08:00:00"), 9.3)
        val w = detectPatterns(r, emptyMap(), day, z).single { it.metric == Metric.WEIGHT }
        assertTrue(!w.up && w.significant)
    }

    @Test fun patternNeedsAWeekOfBaseline() {
        val r = listOf(Reading(t("2026-09-27T08:00:00"), 10.0), Reading(t("2026-09-29T08:00:00"), 5.0))
        assertTrue(detectPatterns(r, emptyMap(), LocalDate.of(2026, 9, 29), z).isEmpty())
    }

    @Test fun recapAndPeakHour() {
        val r = listOf(Reading(t("2026-10-12T05:02:00"), 10.8), Reading(t("2026-10-12T23:05:00"), 10.8), Reading(t("2026-10-12T23:38:00"), 10.8))
        val d = mapOf(r[0].ts to 2.0, r[1].ts to 3.3)
        val st = dayStats(r, d, LocalDate.of(2026, 10, 12), z)
        assertEquals(23, st.peakHour(z))
        assertEquals(5.3, st.totalMinutes!!, 1e-9)
        assertEquals("Mochi: 3 visits · 10.8 · 5.3 min · peak 11pm", recapLine("Mochi", st, fmt, z))
    }

    @Test fun weightOverridesMoveAndDrop() {
        val a = Reading(t("2026-10-12T05:00:00"), 10.8); val b = Reading(t("2026-10-12T06:00:00"), 8.0)
        val h = mapOf("mochi" to listOf(a, b), "luna" to emptyList())
        val o = applyWeightOverrides(h, mapOf(b.ts to "luna"))
        assertEquals(listOf(a), o["mochi"]); assertEquals(listOf(b), o["luna"])
        val u = applyWeightOverrides(h, mapOf(a.ts to ""))
        assertEquals(listOf(b), u["mochi"]); assertEquals(emptyList<Reading>(), u["luna"])
    }

    @Test fun robotAlertsOnlyRecentAndEnabled() {
        val now = t("2026-10-13T08:00:00")
        val acts = listOf(Act(t("2026-10-13T07:00:00"), "DFIFullFlagOn"), Act(t("2026-10-12T07:00:00"), "DFIFullFlagOn"),
            Act(t("2026-10-13T07:30:00"), "robotCycleStatusIdle"), Act(t("2026-10-13T07:40:00"), "bonnetRemovedYes"))
        val r = RobotSnapshot("S1", "Robbie", online = false, lastSeen = t("2026-10-13T06:30:00"), litterState = "OPTIMAL",
            setup = t("2026-09-01T00:00:00"), acts = acts)
        assertTrue(robotAlerts(now, r, RobotTopics(), z).isEmpty()) // everything starts off
        val on = robotAlerts(now, r, RobotTopics(usage = true, errors = true), z).map { it.title }
        assertEquals(listOf("Waste drawer full", "Robbie: Bonnet removed", "Robbie offline"), on) // old drawer row, cycle (off) and deep clean (<90 d) skipped
        val later = robotAlerts(t("2026-12-01T08:00:00"), r.copy(online = true, acts = emptyList()), RobotTopics(usage = true), z)
        assertEquals(listOf("Time for a deep clean"), later.map { it.title })
    }

    @Test fun demoDataIsPlausible() {
        val now = t("2026-10-13T08:00:00")
        val st = demoState(now, z)
        assertEquals(listOf("Mochi", "Luna"), st.pets.map { it.name })
        assertEquals(st, demoState(now, z).copy()) // seeded: same data every launch
        for (p in st.pets) {
            val h = st.history.getValue(p.id)
            assertTrue(h.isNotEmpty() && h.all { it.ts < now })
            assertEquals(st.weights.getValue(p.id).size, h.size) // activity weighings don't double-count
            assertTrue(h.first().ts < now - 400 * 86_400_000L) // over a year of history
            assertTrue(st.durations(p.id).size > h.size * 9 / 10) // nearly every visit gets a duration
        }
    }
}
