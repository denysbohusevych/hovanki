package app.hovanki.shared.lab

import app.hovanki.shared.rules.Smoothings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The technique cards on made-up reports: the numbers decide the verdict. */
class LabCardsTest {
    /** The bands judge a smoothing or a calibration over two distances or more. */
    private val twoDistances = CardFacts(distances = listOf(mapOf("A|B" to 1.0), mapOf("A|B" to 5.0)))

    private fun cards(report: LabReport, facts: CardFacts = CardFacts()) =
        TechniqueCards.build(report, facts).associateBy { it.tech }

    private fun report(
        steps: List<LabReportStep> = emptyList(),
        bands: List<LabReportBands> = emptyList(),
        devices: List<LabReportDevice> = emptyList(),
        carry: List<LabReportCarry> = emptyList(),
        spreads: List<LabReportTouchSpread> = emptyList(),
        calibrations: List<LabReportCalibration> = emptyList(),
        witness: LabReportWitness? = null,
        masks: List<LabReportMask> = emptyList(),
    ) = LabReport(
        runId = "run",
        computedAtMillis = 0,
        steps = steps,
        bands = bands,
        devices = devices,
        carry = carry,
        touchSpreads = spreads,
        touches = if (spreads.isEmpty()) emptyList() else listOf(LabReportTouch("A|B", 1_000)),
        calibrations = calibrations,
        witness = witness,
        masks = masks,
    )

    private fun bands(smoothing: String, calibration: String, exact: Int, seconds: Int = 100): LabReportBands {
        val oneOff = (seconds - exact) / 2
        return LabReportBands(smoothing, calibration, seconds, exact, oneOff, seconds - exact - oneOff, 0.6)
    }

    private fun direction(from: String, to: String, tech: String, readings: Int) = LabReportDirection(
        from = from,
        to = to,
        channel = "android_le/service_data",
        readings = readings,
        perSecond = readings / 10.0,
        medianRssi = -60,
        p80Rssi = -58,
        minRssi = -70,
        maxRssi = -55,
        longestGapMillis = 1_000,
        tech = tech,
    )

    private fun step(vararg directions: LabReportDirection, id: String = "near") =
        LabReportStep(0, id, id, startMillis = 0, endMillis = 10_000, directions = directions.toList())

    @Test
    fun theBestSmoothingStays() {
        val cards = cards(
            report(
                bands = listOf(
                    bands(Smoothings.EMA, Calibrations.NONE, exact = 50),
                    bands(Smoothings.P80, Calibrations.NONE, exact = 70),
                    bands(Smoothings.RATE, Calibrations.NONE, exact = 60),
                    // A calibration's rows don't decide the smoothings.
                    bands(Smoothings.RATE, Calibrations.TOUCH, exact = 95),
                ),
            ),
            twoDistances,
        )
        assertEquals(Verdict.KEEP, cards.getValue(Smoothings.P80).verdict)
        assertEquals(Verdict.DROP, cards.getValue(Smoothings.EMA).verdict)
        assertEquals(Verdict.DROP, cards.getValue(Smoothings.RATE).verdict)
        assertTrue(cards.getValue(Smoothings.EMA).numbers.any { "70.0 %" in it }, "the winner's numbers beside")

        // A tie keeps the game's, and drops nobody: the others are no worse.
        val tie = cards(
            report(bands = Smoothings.ALL.map { bands(it, Calibrations.NONE, exact = 60) }),
            twoDistances,
        )
        assertEquals(Verdict.KEEP, tie.getValue(Smoothings.EMA).verdict)
        assertEquals(Verdict.INSUFFICIENT, tie.getValue(Smoothings.P80).verdict)
        assertEquals(Verdict.INSUFFICIENT, tie.getValue(Smoothings.RATE).verdict)
        assertEquals("ничья с ${Smoothings.EMA}", tie.getValue(Smoothings.RATE).missing)
    }

    @Test
    fun oneDistanceJudgesNoSmoothingAndNoCalibration() {
        // The run of 2026-09-30: every second at 1 m, the model's calibration 11 points worse.
        val cards = cards(
            report(
                bands = Smoothings.ALL.map { bands(it, Calibrations.NONE, exact = 43) } +
                    bands(Smoothings.EMA, Calibrations.MODEL, exact = 32),
                calibrations = listOf(LabReportCalibration(Calibrations.MODEL, mapOf("A|B" to -16.0))),
            ),
            CardFacts(distances = listOf(mapOf("A|B" to 1.0), mapOf("A|B" to 1.0))),
        )
        for (id in Smoothings.ALL + Calibrations.ALL) {
            val card = cards.getValue(id)
            assertEquals(Verdict.INSUFFICIENT, card.verdict, id)
            assertEquals("все секунды на одном расстоянии (1.0 м): нужно хотя бы 2", card.missing, id)
        }
        assertTrue(cards.getValue(Calibrations.MODEL).numbers.any { "32.0 %" in it })
    }

    @Test
    fun noDistancesNoVerdict() {
        val cards = cards(report())
        for (id in Smoothings.ALL + Calibrations.ALL + TechniqueCards.WITNESS) {
            val card = cards.getValue(id)
            assertEquals(Verdict.INSUFFICIENT, card.verdict, id)
            assertNotNull(card.missing, id)
        }
        assertEquals(Smoothings.ALL.size + Calibrations.ALL.size + 1, cards.size, "no channel, no carry: ${cards.keys}")
    }

    @Test
    fun theTouchCalibrationStaysWhenItGainsTenPointsAndTheTouchesAgree() {
        val offsets = listOf(LabReportCalibration(Calibrations.TOUCH, mapOf("A|B" to 12.0, "B|A" to 10.0)))
        val steady = listOf(LabReportTouchSpread("A|B", "A|B", touches = 3, spreadDb = 4, driftDb = 1))
        fun touch(exact: Int, spreads: List<LabReportTouchSpread> = steady) = cards(
            report(
                bands = listOf(
                    bands(Smoothings.EMA, Calibrations.NONE, exact = 50),
                    bands(Smoothings.EMA, Calibrations.TOUCH, exact = exact),
                ),
                spreads = spreads,
                calibrations = offsets,
            ),
            twoDistances,
        )

        val kept = touch(exact = 65)
        assertEquals(Verdict.KEEP, kept.getValue(Calibrations.TOUCH).verdict)
        assertEquals(Verdict.DROP, kept.getValue(Calibrations.NONE).verdict, "a calibration beat none")
        assertEquals(Verdict.INSUFFICIENT, kept.getValue(Calibrations.MODEL).verdict, "no metre steps, no offsets")

        assertEquals(Verdict.DROP, touch(exact = 55).getValue(Calibrations.TOUCH).verdict, "5 points are not 10")
        val loose = listOf(LabReportTouchSpread("A|B", "A|B", touches = 3, spreadDb = 8, driftDb = 7))
        val spread = touch(exact = 70, spreads = loose)
        assertEquals(Verdict.DROP, spread.getValue(Calibrations.TOUCH).verdict, "the touches spread by 8 dB")
        assertEquals(Verdict.KEEP, spread.getValue(Calibrations.NONE).verdict)
        assertTrue(spread.getValue(Calibrations.TOUCH).numbers.any { "разброс 8 dB" in it })

        val once = listOf(LabReportTouchSpread("A|B", "A|B", touches = 1, spreadDb = 0, driftDb = 0))
        assertEquals(Verdict.INSUFFICIENT, touch(exact = 70, spreads = once).getValue(Calibrations.TOUCH).verdict)
    }

    @Test
    fun theModelCalibrationNeedsTenPointsToo() {
        val cards = cards(
            report(
                bands = listOf(
                    bands(Smoothings.EMA, Calibrations.NONE, exact = 40),
                    bands(Smoothings.EMA, Calibrations.MODEL, exact = 50),
                ),
                calibrations = listOf(LabReportCalibration(Calibrations.MODEL, mapOf("A|B" to 3.0))),
            ),
            twoDistances,
        )
        assertEquals(Verdict.KEEP, cards.getValue(Calibrations.MODEL).verdict)
        assertEquals(Verdict.DROP, cards.getValue(Calibrations.NONE).verdict)
    }

    @Test
    fun theChannelsReadingsAtFiveMetres() {
        val facts = CardFacts(
            distances = listOf(mapOf("A|B" to 3.0, "A|droid" to 3.0, "B|droid" to 20.0)),
            onScreen = listOf(setOf("B", "droid")),
            advertised = listOf(mapOf("A" to setOf(SCAN_RESPONSE, BARE), "B" to setOf(NAME))),
        )
        val report = report(
            steps = listOf(
                step(
                    direction("A", "B", SCAN_RESPONSE, 25),
                    direction("A", "droid", SCAN_RESPONSE, 30),
                    direction("A", "B", BARE, 25),
                    direction("A", "droid", BARE, 22),
                    direction("B", "droid", NAME, 3),
                ),
            ),
        )
        val cards = cards(report, facts)
        val scanResponse = cards.getValue(SCAN_RESPONSE)
        assertEquals(Verdict.KEEP, scanResponse.verdict, "$scanResponse")
        assertTrue(scanResponse.numbers.any { "near: A → B, 3.0 м: 2.5 показаний/с" in it }, "${scanResponse.numbers}")
        val bare = cards.getValue(BARE)
        assertEquals(Verdict.DROP, bare.verdict, "passes, but scan_response is better in its worst case: $bare")
        assertTrue(bare.numbers.any { "лучшая раскладка — $SCAN_RESPONSE" in it })
        // B's name at droid 20 m away: heard, so it stays; no 5 m case needed.
        assertEquals(Verdict.KEEP, cards.getValue(NAME).verdict)

        val slow = report(
            steps = listOf(step(direction("A", "B", SCAN_RESPONSE, 12), direction("A", "droid", SCAN_RESPONSE, 30))),
        )
        assertEquals(Verdict.DROP, cards(slow, facts).getValue(SCAN_RESPONSE).verdict, "1.2 readings a second at B")

        // Nobody on the screen: no case.
        val dark = cards(report, facts.copy(onScreen = listOf(emptySet())))
        assertEquals(Verdict.INSUFFICIENT, dark.getValue(SCAN_RESPONSE).verdict)
        assertNull(dark[TechniqueCards.IBEACON], "no card for a channel the logs don't name")
    }

    @Test
    fun aLockedIPhoneRangesTheSeekerForAMinute() {
        val facts = CardFacts(
            distances = listOf(mapOf("B|S" to 3.0)),
            onScreen = listOf(setOf("B")),
            advertised = listOf(mapOf("S" to setOf(TechniqueCards.IBEACON))),
        )
        val screen = report(steps = listOf(step(direction("S", "B", TechniqueCards.IBEACON, 25))))
        fun locked(lastedSeconds: Long?, window: Long = 300_000, report: LabReport = screen) = cards(
            report,
            facts.copy(
                lockedRanging = listOf(
                    LockedRanging("A", "S", 10_000, lastedSeconds?.let { 10_000 + it * 1_000 }, window),
                ),
            ),
        ).getValue(TechniqueCards.IBEACON)

        val kept = locked(90)
        assertEquals(Verdict.KEEP, kept.verdict, "$kept")
        assertTrue(kept.numbers.any { "заблокированный A слышал S 90.0 с после блокировки" in it }, "${kept.numbers}")
        assertTrue(kept.numbers.any { "2.5 показаний/с" in it }, "the on-screen line too: ${kept.numbers}")
        assertEquals(Verdict.DROP, locked(30).verdict)
        assertEquals(Verdict.DROP, locked(null).verdict, "no reading after the lock")
        val slowScreen = report(steps = listOf(step(direction("S", "B", TechniqueCards.IBEACON, 10))))
        assertEquals(Verdict.DROP, locked(90, report = slowScreen).verdict, "1 reading a second on the screen")

        // No iPhone locked long enough: the on-screen line decides, the locked one says so in the numbers.
        val short = locked(30, window = 45_000)
        assertEquals(Verdict.KEEP, short.verdict, "$short")
        assertTrue(short.numbers.any { "окно 45.0 с" in it })
        val none = cards(screen, facts).getValue(TechniqueCards.IBEACON)
        assertEquals(Verdict.KEEP, none.verdict)
        assertTrue(none.numbers.any { "ни один iPhone не блокировался" in it }, "${none.numbers}")
        assertNull(none.missing)

        // A seeker nobody heard at all: the locked silence judges nothing.
        val unheard = cards(
            report(),
            facts.copy(
                techs = setOf(TechniqueCards.IBEACON),
                lockedRanging = listOf(LockedRanging("A", "S", 10_000, null, 300_000)),
            ),
        ).getValue(TechniqueCards.IBEACON)
        assertEquals(Verdict.INSUFFICIENT, unheard.verdict, "$unheard")
        assertTrue(unheard.numbers.any { "iBeacon S не услышал никто" in it }, "${unheard.numbers}")
    }

    @Test
    fun theRegionsEnterWithinThirtySeconds() {
        val heard = report(steps = listOf(step(direction("S", "B", TechniqueCards.IBEACON, 25))))
        fun region(vararg waits: Long?, report: LabReport = heard) = cards(
            report,
            CardFacts(
                regionWaits = waits.mapIndexed { index, wait -> RegionWait(index, "S", "H", 5.0, 1_000, wait) } +
                    RegionWait(0, "S", "far", 40.0, 1_000, null),
            ),
        ).getValue(TechniqueCards.IBEACON_REGION)
        assertEquals(Verdict.KEEP, region(12_000).verdict)
        assertEquals(Verdict.DROP, region(12_000, 45_000).verdict)
        assertEquals(Verdict.DROP, region(null).verdict)
        assertEquals(Verdict.INSUFFICIENT, region().verdict, "only the case 40 m away")

        // The run of 2026-09-30: the Mac's iBeacon, heard by nobody, maybe never on the air.
        val unheard = region(null, report = report())
        assertEquals(Verdict.INSUFFICIENT, unheard.verdict, "$unheard")
        assertTrue(unheard.numbers.any { "iBeacon S не услышал никто" in it }, "${unheard.numbers}")
    }

    @Test
    fun theOverflowMaskFromAPocketAtAnAndroid() {
        val devices = listOf(
            LabReportDevice("A", model = "iPhone15,2", os = "iOS 26.0", events = 10),
            LabReportDevice("droid", model = "Pixel 8", os = "Android 16", events = 10),
        )
        fun overflow(frames: Int, decoded: Int, place: String = LabPlaces.POCKET_FRONT) = cards(
            report(
                steps = listOf(step()),
                devices = devices,
                masks = listOf(LabReportMask("droid", frames, frames, decoded)),
            ),
            CardFacts(
                distances = listOf(mapOf("A|droid" to 5.0)),
                advertised = listOf(mapOf("A" to setOf(TechniqueCards.OVERFLOW))),
                places = listOf(mapOf("A" to place)),
                masks = listOf(mapOf("droid" to frames)),
                tokenMasks = frames,
                tokenMasksDecoded = decoded,
            ),
        ).getValue(TechniqueCards.OVERFLOW)
        assertEquals(Verdict.KEEP, overflow(frames = 5, decoded = 5).verdict, "a frame every 2 s, all read")
        assertEquals(Verdict.DROP, overflow(frames = 2, decoded = 2).verdict, "a frame every 5 s")
        assertEquals(Verdict.DROP, overflow(frames = 10, decoded = 9).verdict, "90 % read right")
        assertEquals(Verdict.INSUFFICIENT, overflow(frames = 5, decoded = 5, place = LabPlaces.TABLE_UP).verdict)
    }

    @Test
    fun theCarryClassifiersAgainstTheMarks() {
        fun rows(tech: String, agreed: Int, other: Int) = listOf(
            LabReportCarry("A", "in_pocket", "in_pocket", agreed, tech),
            LabReportCarry("A", "in_pocket", "in_hand", other, tech),
        )
        val both = cards(report(carry = rows(LabMerge.CARRY_V1, 60, 40) + rows(LabMerge.CARRY_V2, 90, 10)))
        assertEquals(Verdict.KEEP, both.getValue(LabMerge.CARRY_V2).verdict)
        assertEquals(Verdict.DROP, both.getValue(LabMerge.CARRY_V1).verdict)

        val short = cards(report(carry = rows(LabMerge.CARRY_V1, 60, 40) + rows(LabMerge.CARRY_V2, 20, 10)))
        assertEquals(Verdict.INSUFFICIENT, short.getValue(LabMerge.CARRY_V2).verdict, "30 s of v2")
        assertEquals(Verdict.INSUFFICIENT, short.getValue(LabMerge.CARRY_V1).verdict)

        // The run of 2026-09-30: the phone on the table only, never in the pocket.
        fun table(tech: String, said: String, seconds: Int) = LabReportCarry("A", "not_pocket", said, seconds, tech)
        val onTable = cards(
            report(
                carry = listOf(
                    table(LabMerge.CARRY_V1, "unknown", 348),
                    table(LabMerge.CARRY_V1, "none", 215),
                    table(LabMerge.CARRY_V2, "in_hand", 214),
                    table(LabMerge.CARRY_V2, "unknown", 345),
                ),
            ),
        )
        assertEquals(Verdict.INSUFFICIENT, onTable.getValue(LabMerge.CARRY_V2).verdict)
        assertEquals(Verdict.INSUFFICIENT, onTable.getValue(LabMerge.CARRY_V1).verdict)
        assertEquals("в кармане по разметке меньше 60 с (0 с)", onTable.getValue(LabMerge.CARRY_V1).missing)

        val onlyV1 = cards(report(carry = rows(LabMerge.CARRY_V1, 60, 40)))
        assertEquals(Verdict.INSUFFICIENT, onlyV1.getValue(LabMerge.CARRY_V1).verdict)
        assertNull(onlyV1[LabMerge.CARRY_V2])
    }

    @Test
    fun theWitnessIsRightOftenEnough() {
        fun witness(right: Int, wrong: Int) =
            cards(report(witness = LabReportWitness(100, right + wrong + 3, right, wrong, listOf("A|B"))))
                .getValue(TechniqueCards.WITNESS).verdict
        assertEquals(Verdict.KEEP, witness(right = 18, wrong = 2))
        assertEquals(Verdict.DROP, witness(right = 5, wrong = 5))
        assertEquals(Verdict.INSUFFICIENT, witness(right = 5, wrong = 1), "six cases with a truth")
    }

    private companion object {
        const val SCAN_RESPONSE = "ble.service_data.scan_response"
        const val BARE = "ble.service_data.bare"
        const val NAME = "ble.name"
    }
}
