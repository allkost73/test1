package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.SitrakFaultCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Sitrak S7H", appName)
  }

  @Test
  fun `verify sitrak fault code database loaded`() {
    val faults = SitrakFaultCodes.sampleActiveFaults
    assertTrue(faults.isNotEmpty())
    assertTrue(faults.any { it.spnFmi.contains("102") })
  }
}

