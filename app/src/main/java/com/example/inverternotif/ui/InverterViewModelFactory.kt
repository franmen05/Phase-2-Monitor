package com.example.inverternotif.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.inverternotif.api.DessMonitorRepository
import com.example.inverternotif.ui.SessionManager

/**
 * Factory para crear InverterViewModel con inyección del SessionManager.
 *
 * Uso:
 *   val factory = InverterViewModelFactory(SessionManager(this))
 *   val viewModel: InverterViewModel by viewModels { factory }
 */
class InverterViewModelFactory(
    private val sessionManager: SessionManager
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val api  = sessionManager.createApiClient()
            ?: throw IllegalStateException("No hay sesión activa. Llama a SessionManager.saveSession() primero.")
        val repo = DessMonitorRepository(api)
        @Suppress("UNCHECKED_CAST")
        return InverterViewModel(repo) as T
    }
}