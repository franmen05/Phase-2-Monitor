package com.example.inverternotif.api

import com.google.gson.annotations.SerializedName

// ─── Respuesta base ───────────────────────────────────────────────────────────
open class BaseResponse(
    val err: String  = "",   // "0" = éxito
    val desc: String = ""
) {
    val isSuccess get() = err.toString() == "0"
}

// ─── Datos en tiempo real ─────────────────────────────────────────────────────
data class RealtimeResponse(val dat: RealtimeData?) : BaseResponse()

data class RealtimeData(
//    val gts: String?,
    val pars: RealtimePars?
)

data class RealtimePars(
    @SerializedName("gd_") val inputVoltage: List<DeviceParam>?,//Input Voltage
    @SerializedName("sy_") val status: List<DeviceParam>?, //status
    @SerializedName("bt_") val battery: List<DeviceParam>?,//Battery voltage
    @SerializedName("bc_") val outputVoltage: List<DeviceParam>? //Output Voltage
) {
    /**
     * Aplana todas las categorías en una sola lista para mantener compatibilidad
     * con la lógica de búsqueda existente.
     */
    fun allParams(): List<DeviceParam> {
        return (inputVoltage ?: emptyList()) +
               (status ?: emptyList()) +
               (battery ?: emptyList()) +
               (outputVoltage ?: emptyList())
    }
}

data class DeviceParam(
    val id: String = "",
    @SerializedName("par") val name: String = "",
    @SerializedName("val")  val value: String = "",
    val unit: String = ""
)

// ─── Información del Dispositivo (Parámetros completos) ───────────────────────
data class DeviceInfoResponse(val dat: DeviceInfoData?) : BaseResponse()
data class DeviceInfoData(
    @SerializedName("parameter") val parameter: List<DeviceParam>?
)

// ─── Flujo de energía ─────────────────────────────────────────────────────────
data class EnergyFlowResponse(val dat: EnergyFlowData?) : BaseResponse()
data class EnergyFlowData(
    @SerializedName("pvPower")   val pvPower:   String = "0",
    @SerializedName("battPower") val battPower: String = "0",
    @SerializedName("loadPower") val loadPower: String = "0",
    @SerializedName("gridPower") val gridPower: String = "0",
    @SerializedName("soc")       val soc:       String = "0"
)

// ─── Info de planta ───────────────────────────────────────────────────────────
data class PlantInfoResponse(val dat: PlantData?) : BaseResponse()
data class PlantData(val plant: List<PlantInfo>?)
data class PlantInfo(
    val plantid:      String = "",
    val plantname:    String = "",
    val capacity:     String = "",
    val status:       String = "",
    val energy_today: String = "0",
    val energy_total: String = "0"
)

// ─── Datos históricos del día ─────────────────────────────────────────────────
data class DayDataResponse(val dat: DayDataWrapper?) : BaseResponse()
data class DayDataWrapper(val page: PageData?)
data class PageData(val total: Int = 0, val data: List<DayRecord>?)
data class DayRecord(
    val time: String = "",
    val pars: List<DeviceParam>?
)
