package io.github.gccammelli.meowstache

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlin.test.*

class HistoryBoundaryTest {
    private val zone=ZoneOffset.UTC
    private val day=LocalDate(2026,10,13)
    private val now=LocalDateTime(2026,10,13,12,0).toInstant(zone).toEpochMilli()
    private val minute=60_000L
    private val oneDay=86_400_000L
    private val fmt: (Double)->String={ "%.1f".format(it) }

    @Test fun calendarRangesPageAcrossYearAndLeapMonthBoundaries() {
        val leap=LocalDate(2024,3,1)
        val month=buckets(Range.MONTH,leap,null)
        assertEquals(30,month.size); assertEquals(LocalDate(2024,2,29),month.last().end)
        val year=buckets(Range.YEAR,leap,null)
        assertEquals(LocalDate(2023,4,1),year.first().start)
        assertEquals(LocalDate(2024,3,31),year.last().end)
        assertEquals("Apr 2023 – Mar 2024",rangeTitle(year))
        assertEquals("",rangeTitle(emptyList()))
        assertEquals(1,buckets(Range.ALL,day,null).size)
        assertTrue(buckets(Range.ALL,day,day.plusDays(40)).isEmpty())
        assertEquals(day.minusDays(14),pagedToday(Range.WEEK,day,2))
        assertEquals(day.minusDays(60),pagedToday(Range.MONTH,day,2))
        assertEquals(LocalDate(2024,10,13),pagedToday(Range.YEAR,day,2))
        assertEquals(day,pagedToday(Range.ALL,day,2))
    }

    @Test fun unpairedAndStaleActivityNeverInventsVisitLengths() {
        val weight=Reading(now,10.0)
        for (acts in listOf(emptyList(),listOf(Act(now-16*minute,"robotStatusCatDetect"),Act(now+minute,"robotCycleStatusDump")),
            listOf(Act(now-minute,"robotStatusCatDetect"),Act(now-minute/2,"robotCycleStatusDump")),
            listOf(Act(now-minute,"robotStatusCatDetect"),Act(now+91*minute,"robotCycleStatusDump")))) {
            assertTrue(visitDurations(listOf(weight),acts,7).isEmpty())
        }
    }

    @Test fun allPublicActivityLabelsRemainDistinctFromHiddenInternalRows() {
        val labels=mapOf("robotCycleStatusIdle" to "Cycle completed","litterHopperDispensed" to "Litter dispensed",
            "catWeight" to "Weight recorded","robotStatusCatDetect" to "Cat detected","robotCycleStateCatDetect" to "Cycle interrupted",
            "DFIFullFlagOn" to "Waste drawer full","bonnetRemovedYes" to "Bonnet removed","catDetectStuckLaser" to "Cat sensor fault",
            "powerTypeDC" to "Battery backup","globeMotorFaultStatusFaultOvertorqueSlope" to "Globe motor fault")
        for ((event,label) in labels) assertEquals(label,actLabel(Act(now,event)))
        assertNull(actLabel(Act(now,"robotCycleStatusDump")))
    }

    @Test fun invalidHistoricalWeightsAreIgnoredAndUnknownOverridesDoNotCreateReadings() {
        val known=mapOf("pet" to listOf(Reading(now,10.0)))
        val invalid=listOf(null,"?","0","-1").mapIndexed { i,v -> Act(now-(i+1)*oneDay,"catWeight",v) }
        assertEquals(known,attributeWeights(known,invalid))
        assertEquals(emptyMap(),attributeWeights(emptyMap(),invalid))
        assertEquals(known,applyWeightOverrides(known,mapOf(now-oneDay to "other")))
        assertEquals(known,applyWeightOverrides(known,emptyMap()))
    }

    @Test fun robotTopicsRespectTheSixHourWindowAndIncludeFaultAndUpdateDetails() {
        val topics=RobotTopics(usage=true,drawerFull=true,cycleComplete=true,cycleInterrupted=true,visits=true,errors=true,updates=true)
        val events=listOf("DFIFullFlagOn","robotCycleStatusIdle","robotCycleStateCatDetect","bonnetRemovedYes",
            "catDetectStuckLaser","globeMotorFaultStatusFaultOvertorqueSlope","powerTypeDC")
        val snap=RobotSnapshot("robot","Robbie",false,now-25*60*minute,"LOW",now-100*oneDay,
            events.map { Act(now-minute,it) }+listOf(Act(now+1,"DFIFullFlagOn"),Act(now-6*60*minute,"DFIFullFlagOn")),
            firmwareUpdate="new",visits=listOf(VisitNote(now-minute,null,null,1.0),VisitNote(now+1,null,null,null)))
        val notices=robotAlerts(now,snap,topics,zone)
        assertEquals(13,notices.size)
        assertEquals(1,notices.count { it.title=="Waste drawer full" })
        assertTrue(notices.any { it.title=="Low litter" }); assertTrue(notices.any { it.title=="Time for a deep clean" })
        assertTrue(notices.any { it.title=="Software update available" && it.key.endsWith(":new") })
        assertTrue(notices.any { "24 hours" in it.body })
        assertEquals(2,notices.count { it.title=="A cat visited Robbie" })
        assertTrue(robotAlerts(now,snap.copy(lastSeen=null,setup=now+oneDay,acts=emptyList(),visits=emptyList(),firmwareUpdate=null),
            topics.copy(lowLitter=false),zone).isEmpty())
    }

    @Test fun alertThresholdsExcludeFutureReadingsAndUnchangedTargetWeights() {
        val settings=AlertSettings(noVisitEnabled=true,noVisitDays=1,tooManyEnabled=true,tooManyVisits=1,targets=mapOf("pet" to Target(11.0,0)))
        val unchanged=listOf(Reading(now-2*oneDay,10.0),Reading(now-oneDay,10.0))
        val alerts=evaluateAlerts(now,listOf("pet" to "Mochi","absent" to "Luna"),mapOf("pet" to unchanged),settings,fmt,zone)
        assertEquals(1,alerts.size); assertTrue(alerts.single().body.endsWith("1 day."))
        val future=listOf(Reading(now+minute,10.0),Reading(now-10*minute,10.0))
        assertTrue(evaluateAlerts(now,listOf("pet" to "Mochi"),mapOf("pet" to future),settings,fmt,zone).isEmpty())
    }

    @Test fun emptyAndSingleVisitRecapsKeepUnknownDurationsExplicit() {
        val empty=DayStats(day,emptyList())
        assertNull(empty.meanLbs); assertNull(empty.totalMinutes); assertNull(empty.peakHour(zone))
        assertEquals("Mochi: no visits",recapLine("Mochi",empty,fmt,zone))
        val single=DayStats(day,listOf(Visit(now,10.0,null)))
        assertEquals("Mochi: 1 visit · 10.0 · peak 12pm",recapLine("Mochi",single,fmt,zone))
        val tie=DayStats(day,listOf(Visit(now,10.0,null),Visit(now-minute*60,10.0,2.0)))
        assertEquals(11,tie.peakHour(zone))
        assertEquals(listOf("12am","1am","12pm","1pm"),listOf(0,1,12,13).map(::hourLabel))
    }

    @Test fun durationAndWeightPatternsHaveStableKeysAndReadableDescriptions() {
        val duration=Pattern(Metric.DURATION,day,2.0,6.0,true)
        assertEquals("pet|DURATION|2026-10-13",duration.key("pet"))
        assertEquals("Duration: Increased 3X from 2 min to 6 min",duration.text(fmt))
        assertEquals("Weight: Decreased 10% from 10.0 to 9.0",Pattern(Metric.WEIGHT,day,10.0,9.0,true).text(fmt))
        assertEquals("Visits: Increased 100% from 0 to 2",Pattern(Metric.VISITS,day,0.0,2.0,true).text(fmt))
        val readings=(1..28).map { Reading(now-it*oneDay,10.0) }+Reading(now,10.0)
        val durations=readings.associate { it.ts to if(it.ts==now) 8.0 else 2.0 }
        val found=detectPatterns(readings,durations,day,zone)
        assertEquals(listOf(Metric.DURATION),found.map { it.metric }); assertTrue(found.single().significant)
        assertTrue(detectPatterns(emptyList(),emptyMap(),day,zone).isEmpty())
        assertTrue(detectPatterns(readings,emptyMap(),day.minusDays(50),zone).isEmpty())
    }
}
