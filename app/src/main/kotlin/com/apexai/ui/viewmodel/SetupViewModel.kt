package com.apexai.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.apexai.agent.AutonomyPolicy
import com.apexai.agent.PermissionProfile
import com.apexai.config.AppConfig
import com.apexai.provider.ProviderManager
import com.apexai.provider.ProviderInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val appConfig: AppConfig,
    private val providerManager: ProviderManager,
    private val autonomyPolicy: AutonomyPolicy
) : ViewModel() {

    private val _selectedProvider = MutableStateFlow(providerManager.activeType())
    val selectedProvider: StateFlow<String> = _selectedProvider.asStateFlow()

    private val _selectedProfile = MutableStateFlow(PermissionProfile.FULL_AUTO)
    val selectedProfile: StateFlow<PermissionProfile> = _selectedProfile.asStateFlow()

    val availableProviders: List<ProviderInfo> = providerManager.availableProviders()

    fun selectProvider(type: String) {
        _selectedProvider.value = type
        providerManager.switchProvider(type)
    }

    fun saveApiKey(provider: String, apiKey: String) {
        providerManager.setApiKey(provider, apiKey)
    }

    fun saveUserName(name: String) {
        appConfig.userName = name
    }

    fun saveOmnirouteBaseUrl(url: String) {
        appConfig.omnirouteBaseUrl = url
    }

    fun saveCustomBaseUrl(url: String) {
        appConfig.customBaseUrl = url
    }

    fun selectProfile(profile: PermissionProfile) {
        _selectedProfile.value = profile
        appConfig.permissionProfile = profile.name
        autonomyPolicy.applyProfile(profile)
    }

    fun defaultModel(providerType: String): String {
        return availableProviders.find { it.type == providerType }?.defaultModel ?: ""
    }
}
