package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "diagnostic_reports")
data class DiagnosticReportEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val truckModel: String = "SITRAK C7H / S7H 480 6x4",
    val vin: String = "ZZ4256V324HE19028",
    val adapterName: String = "ELM327 Bluetooth Classic",
    val dtcCount: Int = 0,
    val dtcListText: String = "",
    val parametersSnapshot: String = "",
    val status: String = "Диагностика завершена"
)
