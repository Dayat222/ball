package com.utility.screenprotractor

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.utility.screenprotractor.databinding.ActivityMainBinding

/**
 * Entry dashboard activity providing permission checks, overlay start/stop controls,
 * and calibration management.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PrefsManager

    // Permission launcher to handle returning from system overlay settings
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateUIState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PrefsManager(this)

        binding.tvCurrentVersion.text = "Versi Terpasang: v${AppUpdateManager.getInstalledVersionName(this)}"

        setupClickListeners()
        checkUpdateSilently()
    }

    override fun onResume() {
        super.onResume()
        updateUIState()
    }

    private fun setupClickListeners() {
        // Grant permission button
        binding.btnGrantPermission.setOnClickListener {
            requestOverlayPermission()
        }

        // Start Overlay Button
        binding.btnStartOverlay.setOnClickListener {
            if (hasOverlayPermission()) {
                startOverlayService()
                launch8BallPoolOrRemind()
            } else {
                Toast.makeText(
                    this,
                    R.string.toast_permission_needed,
                    Toast.LENGTH_SHORT
                ).show()
                requestOverlayPermission()
            }
        }

        // Stop Overlay Button
        binding.btnStopOverlay.setOnClickListener {
            if (OverlayService.isServiceRunning) {
                val stopIntent = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_STOP_SERVICE
                }
                startService(stopIntent)
                Toast.makeText(this, "Overlay service stopped", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Overlay is not running", Toast.LENGTH_SHORT).show()
            }
            updateUIState()
        }

        // Reset Calibration Button
        binding.btnResetCalibration.setOnClickListener {
            prefsManager.resetBoundaryBox()
            Toast.makeText(
                this,
                "Boundary box calibration reset to default",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Check for Update Button
        binding.btnCheckUpdate.setOnClickListener {
            performManualUpdateCheck()
        }
    }

    private fun checkUpdateSilently() {
        AppUpdateManager.checkForUpdate(this, onResult = { info ->
            if (!isFinishing && !isDestroyed && info.isUpdateAvailable) {
                AppUpdateManager.showUpdateDialog(this, info) {
                    binding.tvUpdateStatus.visibility = View.VISIBLE
                    binding.progressBarUpdate.visibility = View.VISIBLE
                    binding.tvUpdateStatus.text = getString(R.string.update_checking)
                }
            }
        }, onError = {
            // Silent check ignores errors to avoid disturbing user
        })
    }

    private fun performManualUpdateCheck() {
        binding.btnCheckUpdate.isEnabled = false
        binding.tvUpdateStatus.visibility = View.VISIBLE
        binding.tvUpdateStatus.text = getString(R.string.update_checking)
        binding.progressBarUpdate.visibility = View.GONE

        AppUpdateManager.checkForUpdate(this, onResult = { info ->
            binding.btnCheckUpdate.isEnabled = true
            if (info.isUpdateAvailable) {
                binding.tvUpdateStatus.text = "Versi baru ${info.remoteVersionName} tersedia!"
                AppUpdateManager.showUpdateDialog(this, info) {
                    binding.progressBarUpdate.visibility = View.VISIBLE
                }
            } else {
                binding.tvUpdateStatus.visibility = View.GONE
                Toast.makeText(
                    this,
                    getString(R.string.update_already_latest, info.remoteVersionName),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, onError = { error ->
            binding.btnCheckUpdate.isEnabled = true
            binding.tvUpdateStatus.visibility = View.GONE
            Toast.makeText(
                this,
                "${getString(R.string.update_check_failed)}: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        })
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun startOverlayService() {
        val serviceIntent = Intent(this, OverlayService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    /**
     * Requirement: Otomatis membuka game 8 Ball Pool jika terpasang di perangkat.
     * Jika belum terpasang, tampilkan peringatan dialog dan toast.
     */
    private fun launch8BallPoolOrRemind() {
        val gamePackage = getString(R.string.game_package_8ball)
        val launchIntent = try {
            packageManager.getLaunchIntentForPackage(gamePackage)
        } catch (e: Exception) {
            null
        }

        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(launchIntent)
                finish()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Gagal membuka game: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            updateUIState()
            Toast.makeText(
                this,
                R.string.toast_game_not_installed,
                Toast.LENGTH_LONG
            ).show()

            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.game_not_installed_title)
                .setMessage(R.string.game_not_installed_message)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton(R.string.btn_open_play_store) { _, _ ->
                    try {
                        val playStoreIntent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=$gamePackage")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(playStoreIntent)
                    } catch (e: Exception) {
                        val webIntent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=$gamePackage")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(webIntent)
                    }
                }
                .setNegativeButton(R.string.btn_dialog_close, null)
                .show()
        }
    }

    private fun updateUIState() {
        val hasPermission = hasOverlayPermission()
        val isRunning = OverlayService.isServiceRunning

        // Update status dot and text
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                if (hasPermission)
                    ContextCompat.getColor(this@MainActivity, R.color.green_status)
                else
                    ContextCompat.getColor(this@MainActivity, R.color.red_danger)
            )
        }
        binding.indicatorStatus.background = dot

        if (hasPermission) {
            binding.tvPermissionStatus.text = getString(R.string.permission_granted)
            binding.tvPermissionStatus.setTextColor(
                ContextCompat.getColor(this, R.color.green_status)
            )
            binding.btnGrantPermission.visibility = View.GONE
            binding.btnStartOverlay.isEnabled = true
            binding.btnStartOverlay.alpha = 1.0f
        } else {
            binding.tvPermissionStatus.text = getString(R.string.permission_not_granted)
            binding.tvPermissionStatus.setTextColor(
                ContextCompat.getColor(this, R.color.red_danger)
            )
            binding.btnGrantPermission.visibility = View.VISIBLE
            binding.btnStartOverlay.isEnabled = true
            binding.btnStartOverlay.alpha = 0.85f
        }

        // Update stop button visibility / state
        if (isRunning) {
            binding.btnStopOverlay.visibility = View.VISIBLE
            binding.btnStartOverlay.text = "Ruler Running (Restart)"
        } else {
            binding.btnStopOverlay.visibility = View.GONE
            binding.btnStartOverlay.text = getString(R.string.btn_start_overlay)
        }
    }
}
