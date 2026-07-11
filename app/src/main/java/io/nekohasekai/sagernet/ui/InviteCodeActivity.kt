package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.cchr.PrivateSubscriptionManager
import io.nekohasekai.sagernet.databinding.LayoutInviteCodeBinding
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class InviteCodeActivity : ThemedActivity() {

    private lateinit var binding: LayoutInviteCodeBinding
    private var submitting = false
    private val replaceExisting by lazy { intent.getBooleanExtra(EXTRA_REPLACE, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutInviteCodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(if (replaceExisting) R.string.cchr_change_invite else R.string.cchr_invite_guide_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.inviteInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        binding.inviteMessage.setText(
            if (replaceExisting) R.string.cchr_invite_replace_message
            else R.string.cchr_invite_guide_message
        )
        binding.inviteSubmit.setOnClickListener { submitInviteCode() }
    }

    private fun submitInviteCode() {
        if (submitting) return
        val inviteCode = binding.inviteInput.text?.toString()?.trim().orEmpty()
        if (inviteCode.isBlank()) {
            binding.inviteInputLayout.error = getString(R.string.cchr_invite_hint)
            return
        }

        submitting = true
        binding.inviteInputLayout.error = null
        binding.inviteSubmit.isEnabled = false
        binding.inviteSubmit.setText(R.string.cchr_invite_activating)

        runOnDefaultDispatcher {
            val result = runCatching {
                PrivateSubscriptionManager.activateWithInviteCode(inviteCode, replaceExisting)
            }
            onMainDispatcher {
                submitting = false
                binding.inviteSubmit.isEnabled = true
                binding.inviteSubmit.setText(R.string.cchr_invite_submit)

                val error = result.exceptionOrNull()
                if (result.getOrDefault(false)) {
                    snackbar(
                        if (replaceExisting) R.string.cchr_invite_changed
                        else R.string.cchr_invite_activated
                    ).show()
                    finish()
                } else if (error != null) {
                    snackbar(error.readableMessage).show()
                } else {
                    snackbar(R.string.cchr_subscription_update_failed).show()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG)
    }

    companion object {
        const val EXTRA_REPLACE = "replace"
    }
}
