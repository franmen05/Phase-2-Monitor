package com.example.inverternotif.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.inverternotif.api.DessMonitorRepository
import com.example.inverternotif.api.DeviceInfoResponse
import com.example.inverternotif.api.EnergyFlowResponse
import com.example.inverternotif.api.PlantInfoResponse
import com.example.inverternotif.api.RealtimeResponse
import kotlinx.coroutines.launch
import java.time.LocalDate

class InverterViewModel(private val repo: DessMonitorRepository) : ViewModel() {

    private val _realtimeData = MutableLiveData<Result<RealtimeResponse>>()
    val realtimeData: LiveData<Result<RealtimeResponse>> = _realtimeData

    private val _energyFlow = MutableLiveData<Result<EnergyFlowResponse>>()
    val energyFlow: LiveData<Result<EnergyFlowResponse>> = _energyFlow

    private val _plantInfo = MutableLiveData<Result<PlantInfoResponse>>()
    val plantInfo: LiveData<Result<PlantInfoResponse>> = _plantInfo

    private val _deviceInfo = MutableLiveData<Result<DeviceInfoResponse>>()
    val deviceInfo: LiveData<Result<DeviceInfoResponse>> = _deviceInfo

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            _realtimeData.value = repo.getRealtimeData()
//            _energyFlow.value   = repo.getEnergyFlow()
//            _plantInfo.value    = repo.getPlantInfo()
//            _deviceInfo.value   = repo.getDeviceInfo()
            _isLoading.value = false
        }
    }

    fun loadDayData(date: LocalDate) {
        viewModelScope.launch {
            repo.getDayData(date)
        }
    }
}