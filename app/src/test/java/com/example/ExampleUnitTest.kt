package com.example

import com.example.bluetooth.Elm327Manager
import com.example.data.SitrakFaultCodes
import com.example.model.TruckModule
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testParseSteeringAngle_j1939Offset() {
    // 0x7D00 (32000) = 0.0°
    val zeroAngle = Elm327Manager.parseSteeringAngle("62 01 0A 7D 00")
    assertNotNull(zeroAngle)
    assertEquals(0.0f, zeroAngle!!, 0.1f)

    // 0x7D3C (32060) -> (32060 - 32000) * 0.05 = +3.0°
    val rightAngle = Elm327Manager.parseSteeringAngle("62 01 0A 7D 3C")
    assertNotNull(rightAngle)
    assertEquals(3.0f, rightAngle!!, 0.1f)

    // 0x7CC4 (31940) -> (31940 - 32000) * 0.05 = -3.0°
    val leftAngle = Elm327Manager.parseSteeringAngle("62 01 0A 7C C4")
    assertNotNull(leftAngle)
    assertEquals(-3.0f, leftAngle!!, 0.1f)
  }

  @Test
  fun testParseSteeringAngle_signed16Bit() {
    // 0x0000 = 0.0°
    val zero = Elm327Manager.parseSteeringAngle("62 02 00 00 00")
    assertNotNull(zero)
    assertEquals(0.0f, zero!!, 0.1f)

    // 0x0028 (40 dec) -> 4.0°
    val positive = Elm327Manager.parseSteeringAngle("62 02 00 00 28")
    assertNotNull(positive)
    assertEquals(4.0f, positive!!, 0.1f)

    // 0xFFD8 (-40 dec) -> -4.0°
    val negative = Elm327Manager.parseSteeringAngle("62 02 00 FF D8")
    assertNotNull(negative)
    assertEquals(-4.0f, negative!!, 0.1f)
  }

  @Test
  fun testParseSteeringAngle_withCanHeaders() {
    val angleWithHeader = Elm327Manager.parseSteeringAngle("18DAF10B 05 62 01 0A 7D 00")
    assertNotNull(angleWithHeader)
    assertEquals(0.0f, angleWithHeader!!, 0.1f)
  }

  @Test
  fun testParseSteeringAngle_j1939Pgn61469() {
    // PGN 61469 (F01D), SA 0x13, Data: 00 7D -> 0x7D00 = 0.0°
    val zeroPgn = Elm327Manager.parseSteeringAngle("0CF01D13 08 00 7D 00 00 00 00 00 00")
    assertNotNull(zeroPgn)
    assertEquals(0.0f, zeroPgn!!, 0.1f)

    // Data: 3C 7D -> Little Endian 0x7D3C (32060) -> (32060 - 32000) * 0.05 = +3.0°
    val rightPgn = Elm327Manager.parseSteeringAngle("0CF01D13 08 3C 7D 00 00 00 00 00 00")
    assertNotNull(rightPgn)
    assertEquals(3.0f, rightPgn!!, 0.1f)

    // Data: C4 7C -> Little Endian 0x7CC4 (31940) -> (31940 - 32000) * 0.05 = -3.0°
    val leftPgn = Elm327Manager.parseSteeringAngle("0CF01D13 08 C4 7C 00 00 00 00 00 00")
    assertNotNull(leftPgn)
    assertEquals(-3.0f, leftPgn!!, 0.1f)
  }

  @Test
  fun testParseSteeringAngle_kwp2000() {
    val kwpAngle = Elm327Manager.parseSteeringAngle("61 0A 7D 3C")
    assertNotNull(kwpAngle)
    assertEquals(3.0f, kwpAngle!!, 0.1f)
  }

  @Test
  fun testParseSteeringAngle_udsLittleEndian() {
    val leAngle = Elm327Manager.parseSteeringAngle("62 01 0A 3C 7D")
    assertNotNull(leAngle)
    assertEquals(3.0f, leAngle!!, 0.1f)
  }

  @Test
  fun testParseSteeringAngle_cbcuGateway() {
    val cbcuAngle = Elm327Manager.parseSteeringAngle("62 D0 01 7D 3C")
    assertNotNull(cbcuAngle)
    assertEquals(3.0f, cbcuAngle!!, 0.1f)
  }

  @Test
  fun testParseSteeringAngle_rawCanPayloads() {
    // 8-byte raw CAN broadcast payload with headers OFF (ATH0)
    val rightRaw = Elm327Manager.parseSteeringAngle("3C 7D 00 00 00 00 00 00")
    assertNotNull(rightRaw)
    assertEquals(3.0f, rightRaw!!, 0.1f)

    val leftRaw = Elm327Manager.parseSteeringAngle("C4 7C 00 00 00 00 00 00")
    assertNotNull(leftRaw)
    assertEquals(-3.0f, leftRaw!!, 0.1f)

    val centerRaw = Elm327Manager.parseSteeringAngle("00 7D 00 00 00 00 00 00")
    assertNotNull(centerRaw)
    assertEquals(0.0f, centerRaw!!, 0.1f)
  }

  @Test
  fun testParseSteeringAngle_rejectsEchoesAndNegativeResponses() {
    // Command echoes must be rejected to prevent false locking and freezes
    assertNull(Elm327Manager.parseSteeringAngle("ATCRA 0CF01D13\rOK>"))
    assertNull(Elm327Manager.parseSteeringAngle("ATMP F01D 1\rNO DATA>"))
    assertNull(Elm327Manager.parseSteeringAngle("ATSH 18DA0BF1"))
    assertNull(Elm327Manager.parseSteeringAngle("SEARCHING..."))
    assertNull(Elm327Manager.parseSteeringAngle("UNABLE TO CONNECT"))

    // UDS Negative Responses (NRC 7F ...) must be rejected
    assertNull(Elm327Manager.parseSteeringAngle("7F 22 31"))
    assertNull(Elm327Manager.parseSteeringAngle("7F 10 7E"))
    assertNull(Elm327Manager.parseSteeringAngle("18DAF10B 03 7F 22 31"))
  }

  @Test
  fun testIsEcuAliveResponse() {
    // Positive responses
    assertTrue(Elm327Manager.isEcuAliveResponse("50 01 00 32 01 F4"))
    assertTrue(Elm327Manager.isEcuAliveResponse("7E 00"))
    assertTrue(Elm327Manager.isEcuAliveResponse("62 01 0A 7D 00"))
    assertTrue(Elm327Manager.isEcuAliveResponse("43 00"))
    assertTrue(Elm327Manager.isEcuAliveResponse("59 02 FF"))

    // Negative response proves ECU hardware is alive on the CAN bus
    assertTrue(Elm327Manager.isEcuAliveResponse("7F 10 7E"))
    assertTrue(Elm327Manager.isEcuAliveResponse("7F 22 31"))

    // Non-alive responses & AT command echoes ("OK", "BUS INIT: ... OK", etc.) MUST return false
    assertFalse(Elm327Manager.isEcuAliveResponse("NO DATA"))
    assertFalse(Elm327Manager.isEcuAliveResponse("CAN ERROR"))
    assertFalse(Elm327Manager.isEcuAliveResponse("UNABLE TO CONNECT"))
    assertFalse(Elm327Manager.isEcuAliveResponse("?"))
    assertFalse(Elm327Manager.isEcuAliveResponse("OK"))
    assertFalse(Elm327Manager.isEcuAliveResponse("BUS INIT: ... OK"))
    assertFalse(Elm327Manager.isEcuAliveResponse("ATSH 18DA00F1 OK"))
    assertFalse(Elm327Manager.isEcuAliveResponse("SEARCHING..."))
  }

  @Test
  fun testParseSteeringAngle_j1939Vdc2F009_andRejectsNa() {
    // WABCO EBS VDC2 PGN F009 (SA 0x0B): bytes 0..1 = 0x3C 0x7D (LE 0x7D3C -> +3.0°)
    val vdc2Right = Elm327Manager.parseSteeringAngle("18 F0 09 0B 3C 7D FF FF FF FF FF FF")
    assertNotNull(vdc2Right)
    assertEquals(3.0f, vdc2Right!!, 0.1f)

    // SAE J1939 Not Available (0xFF 0xFF) MUST return null (not -0.1°)
    assertNull(Elm327Manager.parseSteeringAngle("18 F0 09 0B FF FF FF FF FF FF FF FF"))
    assertNull(Elm327Manager.parseSteeringAngle("0CF01D13 08 FF FF FF FF FF FF FF FF"))
  }

  @Test
  fun testParseDtcResponse_udsKwpObd() {
    // Multi-frame UDS 19 02 FF response (4 bytes per DTC: DTC_Hi, DTC_Mid, FTB, Status)
    val udsMulti = "00E\n0: 59 02 FF 02 38 13 28\n1: 00 87 11 29 AA AA AA"
    val udsDtcs = SitrakFaultCodes.parseDtcResponse(udsMulti, com.example.model.TruckModule.ECM)
    assertEquals(2, udsDtcs.size)
    assertEquals("P0238", udsDtcs[0].obdCode)
    assertEquals("P0087", udsDtcs[1].obdCode)

    // KWP2000 18 00 FF 00 response: 58 [count=2] [b1 b2 status] x 2
    val kwpRaw = "58 02 50 AC 60 02 38 20"
    val kwpDtcs = SitrakFaultCodes.parseDtcResponse(kwpRaw, com.example.model.TruckModule.EBS)
    assertEquals(2, kwpDtcs.size)
    assertEquals("C10AC", kwpDtcs[0].obdCode)
    assertEquals("P0238", kwpDtcs[1].obdCode)

    // OBD Mode 03 with count byte and without count byte
    val obdWithCount = SitrakFaultCodes.parseDtcResponse("43 02 02 38 00 87", com.example.model.TruckModule.ECM)
    assertEquals(2, obdWithCount.size)
    assertEquals("P0238", obdWithCount[0].obdCode)
    assertEquals("P0087", obdWithCount[1].obdCode)

    val obdNoCount = SitrakFaultCodes.parseDtcResponse("43 02 38 00 87 00 00", com.example.model.TruckModule.ECM)
    assertEquals(2, obdNoCount.size)
  }

  @Test
  fun testParseJ1939DmResponse_singleAndBam() {
    // Single-frame DM1 from WABCO EBS (SA 0x0B): SPN 1807 (0x070F -> 0F 07 0D 01), FMI 13
    val singleDm1 = "18 FECA 0B 04 FF 0F 07 0D 01 FF FF"
    val singleParsed = SitrakFaultCodes.parseJ1939DmResponse(singleDm1)
    assertEquals(1, singleParsed.size)
    assertEquals(TruckModule.EBS, singleParsed[0].module)
    assertEquals("SPN 1807 FMI 13", singleParsed[0].spnFmi)

    val rawSingleDm1 = "18 FE CA 0B 04 FF 0F 07 0D 01 FF FF"
    val rawSingleParsed = SitrakFaultCodes.parseJ1939DmResponse(rawSingleDm1)
    assertEquals(1, rawSingleParsed.size)
    assertEquals("SPN 1807 FMI 13", rawSingleParsed[0].spnFmi)

    // ATSPA multi-line BAM from ECM (SA 0x00)
    val atspaBam = """
      18 FECA 00
      00A
      0: 04 FF 66 00 03 01 9D
      1: 00 01 02 FF FF FF FF
    """.trimIndent()
    val atspaParsed = SitrakFaultCodes.parseJ1939DmResponse(atspaBam)
    assertEquals(2, atspaParsed.size)
    assertEquals("SPN 102 FMI 3", atspaParsed[0].spnFmi)
    assertEquals("SPN 157 FMI 1", atspaParsed[1].spnFmi)

    // Raw CAN TP BAM DM1 from ECM (SA 0x00): SPN 102 FMI 3 (66 00 03 01) + SPN 157 FMI 1 (9D 00 01 02)
    val rawBam = """
      18 EC FF 00 20 0A 00 02 FF CA FE 00
      18 EB FF 00 01 04 FF 66 00 03 01 9D
      18 EB FF 00 02 00 01 02 FF FF FF FF
    """.trimIndent()
    val bamParsed = SitrakFaultCodes.parseJ1939DmResponse(rawBam)
    assertEquals(2, bamParsed.size)
    assertEquals(TruckModule.ECM, bamParsed[0].module)
    assertEquals("SPN 102 FMI 3", bamParsed[0].spnFmi)
    assertEquals("SPN 157 FMI 1", bamParsed[1].spnFmi)
  }
}

