package com.example

import com.example.bluetooth.Elm327Manager
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
}

