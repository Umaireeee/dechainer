package io.github.warleysr.dechainer.viewmodels

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import io.github.warleysr.dechainer.DechainerAccessibilityService
import io.github.warleysr.dechainer.DechainerApplication
import io.github.warleysr.dechainer.data.RegionRuleRepository
import io.github.warleysr.dechainer.models.RegionRule
import java.util.UUID

class RegionBlockerViewModel : ViewModel() {
    private val context = DechainerApplication.getInstance()
    private val packageManager = context.packageManager

    val rules = mutableStateListOf<RegionRule>().apply { addAll(RegionRuleRepository.load(context)) }

    fun appLabel(packageName: String): String = try {
        packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
    } catch (_: Exception) {
        packageName
    }

    /** Returns false when the accessibility service isn't running, so nothing can be captured. */
    fun startCapture(): Boolean = DechainerAccessibilityService.scheduleCapture(CAPTURE_DELAY_MS)

    fun clearCapture() = DechainerAccessibilityService.clearCapture()

    fun addRule(node: DechainerAccessibilityService.Companion.CapturedNode, matchText: Boolean, extendDown: Boolean, invisible: Boolean) {
        val pkg = DechainerAccessibilityService.capturedPackage ?: return
        val text = if (matchText || node.viewId == null) node.text else null
        val rule = RegionRule(
            id = UUID.randomUUID().toString(),
            packageName = pkg,
            viewId = node.viewId,
            text = text,
            label = node.viewId?.substringAfter('/') ?: node.text.orEmpty(),
            extendDown = extendDown,
            activityClass = DechainerAccessibilityService.capturedActivity,
            invisible = invisible
        )
        if (rules.none { it.packageName == pkg && it.viewId == rule.viewId && it.text == rule.text && it.extendDown == extendDown &&
                it.activityClass == rule.activityClass }) {
            rules.add(rule)
            RegionRuleRepository.save(context, rules)
        }
    }

    fun setInvisible(rule: RegionRule, invisible: Boolean) {
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index < 0) return
        rules[index] = rules[index].copy(invisible = invisible)
        RegionRuleRepository.save(context, rules)
    }

    fun removeRule(rule: RegionRule) {
        if (rules.remove(rule)) RegionRuleRepository.save(context, rules)
    }

    companion object {
        const val CAPTURE_DELAY_MS = 8_000L
    }
}
