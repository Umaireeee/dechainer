package io.github.warleysr.dechainer

import android.app.admin.DevicePolicyIdentifiers
import android.app.admin.PolicyUpdateReceiver
import android.app.admin.PolicyUpdateResult
import android.app.admin.TargetUser
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.warleysr.dechainer.data.AppRepository

// Only delivered on API 34+ (UPSIDE_DOWN_CAKE). Reports the FINAL, authoritative
// result of policy changes (e.g. setApplicationHidden), which can differ from
// the boolean those DevicePolicyManager calls return synchronously (that
// return value just means "request accepted", not "policy applied").
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class DechainerPolicyUpdateReceiver : PolicyUpdateReceiver() {

    override fun onPolicySetResult(
        context: Context,
        policyIdentifier: String,
        additionalPolicyParams: Bundle,
        targetUser: TargetUser,
        policyUpdateResult: PolicyUpdateResult
    ) {
        log("onPolicySetResult", policyIdentifier, additionalPolicyParams, targetUser, policyUpdateResult)
    }

    override fun onPolicyChanged(
        context: Context,
        policyIdentifier: String,
        additionalPolicyParams: Bundle,
        targetUser: TargetUser,
        policyUpdateResult: PolicyUpdateResult
    ) {
        log("onPolicyChanged", policyIdentifier, additionalPolicyParams, targetUser, policyUpdateResult)
    }

    private fun log(
        callback: String,
        policyIdentifier: String,
        additionalPolicyParams: Bundle,
        targetUser: TargetUser,
        policyUpdateResult: PolicyUpdateResult
    ) {
        val packageName = additionalPolicyParams.getString(EXTRA_PACKAGE_NAME)
        Log.d(
            "DechainerPolicyUpdate",
            "$callback: policy=$policyIdentifier package=$packageName " +
                "user=${describeTargetUser(targetUser)} result=${describeResult(policyUpdateResult)}"
        )

        // The final/authoritative result can disagree with what setApplicationHidden()
        // returned synchronously (e.g. another admin overriding it later). Drop the
        // cache so the next read reflects the real PackageManager state instead of
        // whatever AppRepository optimistically assumed.
        if (policyIdentifier == DevicePolicyIdentifiers.APPLICATION_HIDDEN_POLICY) {
            AppRepository.invalidateCache()
        }
    }

    private fun describeResult(result: PolicyUpdateResult): String = when (result.resultCode) {
        PolicyUpdateResult.RESULT_POLICY_SET -> "RESULT_POLICY_SET"
        PolicyUpdateResult.RESULT_POLICY_CLEARED -> "RESULT_POLICY_CLEARED"
        PolicyUpdateResult.RESULT_FAILURE_CONFLICTING_ADMIN_POLICY -> "RESULT_FAILURE_CONFLICTING_ADMIN_POLICY"
        PolicyUpdateResult.RESULT_FAILURE_STORAGE_LIMIT_REACHED -> "RESULT_FAILURE_STORAGE_LIMIT_REACHED"
        PolicyUpdateResult.RESULT_FAILURE_HARDWARE_LIMITATION -> "RESULT_FAILURE_HARDWARE_LIMITATION"
        else -> "RESULT_FAILURE_UNKNOWN(${result.resultCode})"
    }

    private fun describeTargetUser(targetUser: TargetUser): String = when (targetUser) {
        TargetUser.GLOBAL -> "GLOBAL"
        TargetUser.LOCAL_USER -> "LOCAL_USER"
        TargetUser.PARENT_USER -> "PARENT_USER"
        TargetUser.UNKNOWN_USER -> "UNKNOWN_USER"
        else -> targetUser.toString()
    }

}
