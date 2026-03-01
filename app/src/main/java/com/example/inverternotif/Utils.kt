package com.example.inverternotif

fun calculateBatteryPercentage(voltage: Float): Int {
    val multiplier = when {
        voltage > 40f -> 4f
        voltage > 20f -> 2f
        else -> 1f
    }
    val v = voltage / multiplier
    return when {
        v >= 13.4f -> 100
        v >= 13.2f -> 95
        v >= 13.1f -> 90
        v >= 13.05f -> 80
        v >= 13.0f -> 70
        v >= 12.95f -> 60
        v >= 12.9f -> 50
        v >= 12.85f -> 40
        v >= 12.8f -> 30
        v >= 12.7f -> 20
        v >= 12.4f -> 10
        v >= 12.0f -> 5
        else -> 0
    }
}
