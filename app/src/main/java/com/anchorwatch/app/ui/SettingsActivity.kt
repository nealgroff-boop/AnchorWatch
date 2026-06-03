package com.anchorwatch.app.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.anchorwatch.app.R
import com.anchorwatch.app.repository.SettingsRepository
import com.anchorwatch.app.service.AnchorAlarmService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var repo: SettingsRepository

    private val ringtoneLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri? = result.data?.getParcelableExtra(
                RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            lifecycleScope.launch {
                repo.setAlarmSoundUri(uri?.toString() ?: "")
                val ringtone = if (uri != null)
                    RingtoneManager.getRingtone(this@SettingsActivity, uri)
                else null
                findViewById<TextView>(R.id.tvAlarmSound).text =
                    ringtone?.getTitle(this@SettingsActivity) ?: "No sound selected"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.title = "Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        repo = SettingsRepository(this)

        val etGpsRefresh        = findViewById<EditText>(R.id.etGpsRefresh)
        val etAlarmRadius       = findViewById<EditText>(R.id.etAlarmRadius)
        val etTrackMinDistance  = findViewById<EditText>(R.id.etTrackMinDistance)
        val etSmsNumber         = findViewById<EditText>(R.id.etSmsNumber)
        val etSmsKeyword        = findViewById<EditText>(R.id.etSmsKeyword)
        val btnAlarmSound       = findViewById<Button>(R.id.btnAlarmSound)
        val tvAlarmSound        = findViewById<TextView>(R.id.tvAlarmSound)
        val btnTestSms          = findViewById<Button>(R.id.btnTestSms)
        val btnSave             = findViewById<Button>(R.id.btnSave)
        val layoutMapbox        = findViewById<View>(R.id.layoutMapbox)
        val etMapboxToken       = findViewById<EditText>(R.id.etMapboxToken)

        lifecycleScope.launch {
            etGpsRefresh.setText((repo.gpsRefreshInterval.first() / 1000).toString())
            etAlarmRadius.setText(repo.alarmRadius.first().toInt().toString())
            etTrackMinDistance.setText(repo.trackMinDistance.first().toInt().toString())
            etSmsNumber.setText(repo.smsNumber.first())
            etSmsKeyword.setText(repo.smsKeyword.first())

            val soundUri = repo.alarmSoundUri.first()
            tvAlarmSound.text = if (soundUri.isNotEmpty()) {
                RingtoneManager.getRingtone(this@SettingsActivity, Uri.parse(soundUri))
                    ?.getTitle(this@SettingsActivity) ?: "Custom sound selected"
            } else {
                "No sound selected"
            }

            // Only show the Mapbox section if the user has previously set a token
            if (repo.mapboxTokenSet.first()) {
                layoutMapbox.visibility = View.VISIBLE
                etMapboxToken.setText(repo.mapboxToken.first())
            }
        }

        btnAlarmSound.setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Alarm Sound")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            }
            ringtoneLauncher.launch(intent)
        }

        btnTestSms.setOnClickListener {
            startService(Intent(this, AnchorAlarmService::class.java)
                .apply { action = AnchorAlarmService.ACTION_TEST_SMS })
        }

        btnSave.setOnClickListener {
            lifecycleScope.launch {
                val refreshSecs = etGpsRefresh.text.toString().toLongOrNull() ?: 3L
                repo.setGpsRefreshInterval(refreshSecs * 1000L)
                repo.setAlarmRadius(
                    etAlarmRadius.text.toString().toFloatOrNull() ?: 50f)
                repo.setTrackMinDistance(
                    etTrackMinDistance.text.toString().toFloatOrNull() ?: 5f)
                repo.setSmsNumber(etSmsNumber.text.toString().trim())
                repo.setSmsKeyword(
                    etSmsKeyword.text.toString().trim().uppercase())

                // Save updated Mapbox token if the section is visible and has content
                if (layoutMapbox.visibility == View.VISIBLE) {
                    val token = etMapboxToken.text.toString().trim()
                    if (token.isNotEmpty()) {
                        if (token.startsWith("pk.")) {
                            repo.setMapboxToken(token)
                        } else {
                            Toast.makeText(
                                this@SettingsActivity,
                                "Invalid token — Mapbox tokens start with pk.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@launch
                        }
                    }
                }

                Toast.makeText(this@SettingsActivity, "Settings saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
