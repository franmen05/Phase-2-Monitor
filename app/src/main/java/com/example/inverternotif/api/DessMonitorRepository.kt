package com.example.inverternotif.api

import com.google.gson.Gson
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Capa intermedia entre la API y la UI.
 * Parsea el JSON y expone datos tipados.
 *
 * Uso en ViewModel:
 *   val repo = DessMonitorRepository(sessionManager.createApiClient()!!)
 *   val data = repo.getRealtimeData()
 */
class DessMonitorRepository(private val api: DessMonitorApi) {

    private val gson = Gson()

    suspend fun getRealtimeData(): Result<RealtimeResponse> = runCatching {
//        Log.d("XD DessMonitorApi", "Realtime data: $apiCall")
        gson.fromJson(api.getRealtimeData(), RealtimeResponse::class.java)
    }
//
//    suspend fun getEnergyFlow(): Result<EnergyFlowResponse> = runCatching {
//        gson.fromJson(api.getEnergyFlow(), EnergyFlowResponse::class.java)
//    }
//
//    suspend fun getPlantInfo(): Result<PlantInfoResponse> = runCatching {
//        gson.fromJson(api.getPlantInfo(), PlantInfoResponse::class.java)
//    }

    suspend fun getDayData(date: LocalDate = LocalDate.now()): Result<DayDataResponse> = runCatching {
        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        gson.fromJson(api.getDayData(dateStr), DayDataResponse::class.java)
    }
}
