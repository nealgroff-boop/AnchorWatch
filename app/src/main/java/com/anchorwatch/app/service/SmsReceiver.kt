package com.anchorwatch.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import android.os.Build
import android.util.Log
import com.anchorwatch.app.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class SmsReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val repo = SettingsRepository(context)

        // goAsync() tells Android the receiver is still working after onReceive() returns,
        // keeping the process alive until finish() is called.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val posKeyword = repo.smsKeyword.first().uppercase()

                for (message in messages) {
                    val body = message.messageBody.trim().uppercase()
                    val sender = message.originatingAddress ?: ""
                    Log.d(TAG, "SMS from $sender: $body")

                    if (body.contains(posKeyword)) {
                        val location = GpsService.currentLocation.value
                        if (location != null) {
                            val response =
                                "ANCHORWATCH POSITION: Accuracy: ${location.accuracy.toInt()}m. " +
                                "https://maps.google.com/maps?q=" +
                                "${"%.6f".format(location.latitude)}," +
                                "${"%.6f".format(location.longitude)}"
                            sendSms(context, sender, response)
                        }
                    }
                }
            } finally {
                // Always release the wakelock Android is holding for us
                pendingResult.finish()
            }
        }
    }

    private fun sendSms(context: Context, number: String, message: String) {
        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(number, null, message, null, null)
            Log.d(TAG, "Reply sent to $number")
        } catch (e: Exception) {
            Log.e(TAG, "SMS reply failed: ${e.message}")
        }
    }
}
