package com.example.inverternotif.ui

import android.content.Context
import androidx.core.content.edit
import com.example.inverternotif.api.DessMonitorApi

/**
 * Guarda y recupera el token/secret de sesión en SharedPreferences.
 * Cuando el token expire → clearSession() y pedir login de nuevo.
 */
class SessionManager(context: Context) {

    private val prefs = context.getSharedPreferences("DessMonitorSession", Context.MODE_PRIVATE)

    fun saveSession(token: String, secret: String) {
        prefs.edit { putString("token", token).putString("secret", secret) }
    }

    fun getToken(): String?  = prefs.getString("token", null)
    fun getSecret(): String? = prefs.getString("secret", null)
    fun hasSession(): Boolean = getToken() != null && getSecret() != null
    fun clearSession() = prefs.edit { clear() }

    fun createApiClient(): DessMonitorApi? {
        val token  = getToken()  ?: return null
        val secret = getSecret() ?: return null
        return DessMonitorApi(token = token, secret = secret)
    }
}