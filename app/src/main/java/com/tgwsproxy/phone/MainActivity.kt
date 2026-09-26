package com.tgwsproxy.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tgwsproxy.core.ProxyConfig
import com.tgwsproxy.core.Stats
import com.tgwsproxy.phone.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings
    private val handler = Handler(Looper.getMainLooper())
    private var shownLogVersion = -1L
    private val stateListener: () -> Unit = { handler.post { refreshStatus() } }

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshLogs()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        binding.toggleButton.setOnClickListener {
            if (ProxyService.isRunning || ProxyService.starting) ProxyService.stop(this) else startProxy()
            handler.postDelayed({ refreshStatus() }, 150)
        }
        binding.addToTelegramButton.setOnClickListener { openInTelegram() }
        binding.copyLinkButton.setOnClickListener { copyToClipboard(currentLink()) }
        binding.batteryButton.setOnClickListener { requestBatteryExemption() }
        binding.settingsToggle.setOnClickListener {
            val c = binding.settingsContainer
            c.visibility = if (c.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.regenSecretButton.setOnClickListener {
            binding.secretInput.setText(ProxyConfig.randomSecret())
        }
        binding.saveButton.setOnClickListener { saveSettings() }
        binding.resetButton.setOnClickListener { resetSettings() }
        binding.copyLogsButton.setOnClickListener { copyToClipboard(LogBuffer.snapshot()) }
        binding.clearLogsButton.setOnClickListener { LogBuffer.clear() }

        loadSettingsIntoForm()
        requestNotificationPermission()
        if (intent?.getBooleanExtra(EXTRA_START, false) == true) startProxy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START, false)) startProxy()
    }

    override fun onStart() {
        super.onStart()
        ProxyService.addListener(stateListener)
        handler.post(ticker)
    }

    override fun onStop() {
        ProxyService.removeListener(stateListener)
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    private fun startProxy() {
        try {
            settings.toConfig()
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: e.toString())
            return
        }
        ProxyService.start(this)
    }

    private fun currentLink(): String = ProxyConfig(port = settings.port, secret = settings.secret).telegramLink()

    private fun refreshStatus() {
        val running = ProxyService.isRunning
        val starting = ProxyService.starting
        binding.statusText.text = getString(
            when {
                running -> R.string.status_running
                starting -> R.string.status_starting
                else -> R.string.status_stopped
            },
        )
        binding.toggleButton.text = getString(if (running || starting) R.string.action_stop else R.string.action_start)
        val server = ProxyService.server
        binding.statsText.text = when {
            server != null -> with(server.stats) {
                getString(
                    R.string.stats_line,
                    connectionsActive.get(), connectionsTotal.get(),
                    connectionsWs.get(), connectionsCfProxy.get(), connectionsTcpFallback.get(),
                    Stats.humanBytes(bytesUp.get()), Stats.humanBytes(bytesDown.get()),
                )
            }
            ProxyService.lastError != null -> getString(R.string.status_error, ProxyService.lastError)
            else -> getString(R.string.status_hint)
        }
        binding.linkText.text = currentLink()
        val pm = getSystemService(PowerManager::class.java)
        binding.batteryButton.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    private fun refreshLogs() {
        val v = LogBuffer.version
        if (v == shownLogVersion) return
        shownLogVersion = v
        val scroll = binding.logScroll
        val atBottom = scroll.getChildAt(0).bottom - (scroll.height + scroll.scrollY) < 48
        binding.logText.text = LogBuffer.snapshot()
        if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun loadSettingsIntoForm() {
        binding.portInput.setText(settings.port.toString())
        binding.secretInput.setText(settings.secret)
        binding.dcIpsInput.setText(settings.dcIps)
        binding.cfProxySwitch.isChecked = settings.cfProxy
        binding.cfDomainsInput.setText(settings.cfDomains)
        binding.cfWorkersInput.setText(settings.cfWorkerDomains)
        binding.poolInput.setText(settings.poolSize.toString())
        binding.autostartSwitch.isChecked = settings.autostart
        binding.verboseSwitch.isChecked = settings.verbose
    }

    private fun saveSettings() {
        val port = binding.portInput.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1024..65535) {
            toast(getString(R.string.error_port))
            return
        }
        val secret = binding.secretInput.text.toString().trim().lowercase()
        if (!ProxyConfig.isValidSecret(secret)) {
            toast(getString(R.string.error_secret))
            return
        }
        val dcIps = binding.dcIpsInput.text.toString().trim()
        try {
            ProxyConfig.parseDcIpList(dcIps)
        } catch (e: IllegalArgumentException) {
            toast(e.message ?: getString(R.string.error_dc_ips))
            return
        }
        val pool = binding.poolInput.text.toString().trim().toIntOrNull()?.coerceIn(0, 8) ?: 2

        val linkChanged = port != settings.port || secret != settings.secret
        settings.port = port
        settings.secret = secret
        settings.dcIps = dcIps
        settings.cfProxy = binding.cfProxySwitch.isChecked
        settings.cfDomains = binding.cfDomainsInput.text.toString().trim()
        settings.cfWorkerDomains = binding.cfWorkersInput.text.toString().trim()
        settings.poolSize = pool
        settings.autostart = binding.autostartSwitch.isChecked
        settings.verbose = binding.verboseSwitch.isChecked
        LogBuffer.verbose = settings.verbose
        loadSettingsIntoForm()

        if (ProxyService.isRunning || ProxyService.starting) {
            ProxyService.stop(this)
            handler.postDelayed({ startProxy() }, 500)
        }
        toast(getString(if (linkChanged) R.string.saved_link_changed else R.string.saved))
        refreshStatus()
    }

    private fun resetSettings() {
        AlertDialog.Builder(this)
            .setMessage(R.string.reset_confirm)
            .setPositiveButton(R.string.action_reset) { _, _ ->
                binding.portInput.setText(Settings.DEFAULT_PORT.toString())
                binding.dcIpsInput.setText(ProxyConfig.formatDcIpList(ProxyConfig.DEFAULT_DC_REDIRECTS))
                binding.cfProxySwitch.isChecked = true
                binding.cfDomainsInput.setText("")
                binding.cfWorkersInput.setText("")
                binding.poolInput.setText("2")
                saveSettings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openInTelegram() {
        if (!ProxyService.isRunning && !ProxyService.starting) startProxy()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentLink())))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.error_no_telegram))
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (e: ActivityNotFoundException) {
            runCatching { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("tg-ws-proxy", text))
        if (Build.VERSION.SDK_INT < 33) toast(getString(R.string.copied))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_START = "start"
    }
}
