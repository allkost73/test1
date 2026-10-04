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
}

