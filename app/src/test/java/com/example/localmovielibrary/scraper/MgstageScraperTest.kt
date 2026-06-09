package com.example.localmovielibrary.scraper

import org.junit.Assert.assertEquals
import org.junit.Test

class MgstageScraperTest {
    @Test
    fun normalizesAllDefaultNumericMakerPrefixes() {
        val cases = mapOf(
            "SHN-045" to "116SHN-045",
            "GANA-2556" to "200GANA-2556",
            "CUTE-953" to "229SCUTE-953",
            "LUXU-2556" to "259LUXU-2556",
            "ARA-094" to "261ARA-094",
            "DCV-102" to "277DCV-102",
            "EWDX-400" to "299EWDX-400",
            "MAAN-673" to "300MAAN-673",
            "MIUM-745" to "300MIUM-745",
            "NTK-635" to "300NTK-635",
            "KIRAY-128" to "314KIRAY-128",
            "KJO-002" to "326KJO-002",
            "NAMA-077" to "332NAMA-077",
            "KNB-172" to "336KNB-172",
            "SIMM-662" to "345SIMM-662",
            "NTR-001" to "348NTR-001",
            "JAC-034" to "390JAC-034",
            "KIWVR-254" to "408KIWVR-254",
            "INST-202" to "413INST-202",
            "SRYA-015" to "417SRYA-015",
            "SUKE-086" to "428SUKE-086",
            "MFC-142" to "435MFC-142",
            "HHH-027" to "451HHH-027",
            "TEN-024" to "459TEN-024",
            "MLA-043" to "476MLA-043",
            "SGK-054" to "483SGK-054",
            "GCB-015" to "485GCB-015",
            "SEI-001" to "502SEI-001",
            "STCV-009" to "529STCV-009",
            "MY-425" to "292MY-425",
            "DANDY-852A" to "104DANDY-852A",
            "ICHK-018" to "368ICHK-018"
        )

        cases.forEach { (input, expected) ->
            assertEquals(expected, normalizeMgstageSearchNumber(input))
        }
    }

    @Test
    fun normalizesMiumSearchNumberWithNumericMakerPrefix() {
        assertEquals("300MIUM-001", normalizeMgstageSearchNumber("MIUM-001"))
        assertEquals("300MIUM-001", normalizeMgstageSearchNumber("mium001"))
    }

    @Test
    fun normalizesGanaSearchNumberWithNumericMakerPrefix() {
        assertEquals("200GANA-3403", normalizeMgstageSearchNumber("GANA-3403"))
        assertEquals("200GANA-3403", normalizeMgstageSearchNumber("gana3403"))
    }

    @Test
    fun keepsAlreadyPrefixedMgstageSearchNumber() {
        assertEquals("300MIUM-001", normalizeMgstageSearchNumber("300MIUM-001"))
        assertEquals("200GANA-3403", normalizeMgstageSearchNumber("200GANA-3403"))
    }

    @Test
    fun acceptsRemoteSearchPrefixAliases() {
        assertEquals(
            "123ABC-777",
            normalizeMgstageSearchNumber("ABC-777", mapOf("ABC" to "123ABC"))
        )
    }
}
