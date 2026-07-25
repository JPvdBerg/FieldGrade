package com.fieldgrade.app.control

import kotlin.math.abs
import kotlin.math.roundToInt

data class ControlTuning(
    val deadbandMm: Int = 8,
    val proportionalPerMm: Double = 12.0,
    val maxRequest: Int = 1000
)

class ControlEngine(private val tuning: ControlTuning = ControlTuning()) {
    fun autoRequest(cutFillMm: Int): Int {
        if (abs(cutFillMm) <= tuning.deadbandMm) return 0
        return (cutFillMm * tuning.proportionalPerMm)
            .roundToInt()
            .coerceIn(-tuning.maxRequest, tuning.maxRequest)
    }
}
