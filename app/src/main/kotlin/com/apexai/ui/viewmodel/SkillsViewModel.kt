package com.apexai.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.apexai.skills.Skill
import com.apexai.skills.SkillRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SkillsViewModel @Inject constructor(
    private val skillRegistry: SkillRegistry
) : ViewModel() {

    val skills: StateFlow<List<Skill>> = skillRegistry.skills

    init {
        skillRegistry.loadBundledSkills()
    }

    fun addSkill(name: String, description: String, trigger: String, stepsText: String) {
        val steps = stepsText.lines().filter { it.isNotBlank() }
        val skill = Skill(
            name = name,
            description = description,
            trigger = trigger,
            steps = steps
        )
        skillRegistry.addSkill(skill)
    }

    fun removeSkill(name: String) {
        skillRegistry.removeSkill(name)
    }
}
