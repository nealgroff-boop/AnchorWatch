package com.anchorwatch.app.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "anchorwatch_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val GPS_REFRESH_INTERVAL = longPreferencesKey("gps_refresh_interval")
        val ALARM_RADIUS = floatPreferencesKey("alarm_radius")
        val SMS_NUMBER = stringPreferencesKey("sms_number")
        val SMS_KEYWORD = stringPreferencesKey("sms_keyword")
        val ALARM_SOUND_URI = stringPreferencesKey("alarm_sound_uri")
        val TRACK_MIN_DISTANCE = floatPreferencesKey("track_min_distance")

        // Saved anchor position
        val SAVED_ANCHOR_LAT = doublePreferencesKey("saved_anchor_lat")
        val SAVED_ANCHOR_LON = doublePreferencesKey("saved_anchor_lon")
        val SAVED_ANCHOR_SET = booleanPreferencesKey("saved_anchor_set")

        // Mapbox token — stored only after the user sets it via the satellite dialog
        val MAPBOX_TOKEN = stringPreferencesKey("mapbox_token")
        // Flag that unlocks the token field in Settings — set true once a token is saved
        val MAPBOX_TOKEN_SET = booleanPreferencesKey("mapbox_token_set")

        const val DEFAULT_GPS_REFRESH_MS = 3000L
        const val DEFAULT_ALARM_RADIUS_M = 50f
        const val DEFAULT_SMS_NUMBER = ""
        const val DEFAULT_SMS_KEYWORD = "POSITION"
        const val DEFAULT_ALARM_SOUND_URI = ""
        const val DEFAULT_TRACK_MIN_DISTANCE_M = 5f
    }

    val gpsRefreshInterval: Flow<Long> = context.dataStore.data
        .map { it[GPS_REFRESH_INTERVAL] ?: DEFAULT_GPS_REFRESH_MS }

    val alarmRadius: Flow<Float> = context.dataStore.data
        .map { it[ALARM_RADIUS] ?: DEFAULT_ALARM_RADIUS_M }

    val smsNumber: Flow<String> = context.dataStore.data
        .map { it[SMS_NUMBER] ?: DEFAULT_SMS_NUMBER }

    val smsKeyword: Flow<String> = context.dataStore.data
        .map { it[SMS_KEYWORD] ?: DEFAULT_SMS_KEYWORD }

    val alarmSoundUri: Flow<String> = context.dataStore.data
        .map { it[ALARM_SOUND_URI] ?: DEFAULT_ALARM_SOUND_URI }

    val trackMinDistance: Flow<Float> = context.dataStore.data
        .map { it[TRACK_MIN_DISTANCE] ?: DEFAULT_TRACK_MIN_DISTANCE_M }

    val savedAnchorLat: Flow<Double> = context.dataStore.data
        .map { it[SAVED_ANCHOR_LAT] ?: 0.0 }

    val savedAnchorLon: Flow<Double> = context.dataStore.data
        .map { it[SAVED_ANCHOR_LON] ?: 0.0 }

    val savedAnchorSet: Flow<Boolean> = context.dataStore.data
        .map { it[SAVED_ANCHOR_SET] ?: false }

    val mapboxToken: Flow<String> = context.dataStore.data
        .map { it[MAPBOX_TOKEN] ?: "" }

    val mapboxTokenSet: Flow<Boolean> = context.dataStore.data
        .map { it[MAPBOX_TOKEN_SET] ?: false }

    suspend fun setGpsRefreshInterval(value: Long) {
        context.dataStore.edit { it[GPS_REFRESH_INTERVAL] = value }
    }

    suspend fun setAlarmRadius(value: Float) {
        context.dataStore.edit { it[ALARM_RADIUS] = value }
    }

    suspend fun setSmsNumber(value: String) {
        context.dataStore.edit { it[SMS_NUMBER] = value }
    }

    suspend fun setSmsKeyword(value: String) {
        context.dataStore.edit { it[SMS_KEYWORD] = value }
    }

    suspend fun setAlarmSoundUri(value: String) {
        context.dataStore.edit { it[ALARM_SOUND_URI] = value }
    }

    suspend fun setTrackMinDistance(value: Float) {
        context.dataStore.edit { it[TRACK_MIN_DISTANCE] = value }
    }

    suspend fun saveAnchorPosition(lat: Double, lon: Double) {
        context.dataStore.edit {
            it[SAVED_ANCHOR_LAT] = lat
            it[SAVED_ANCHOR_LON] = lon
            it[SAVED_ANCHOR_SET] = true
        }
    }

    suspend fun clearSavedAnchor() {
        context.dataStore.edit {
            it[SAVED_ANCHOR_LAT] = 0.0
            it[SAVED_ANCHOR_LON] = 0.0
            it[SAVED_ANCHOR_SET] = false
        }
    }

    /** Save the Mapbox token and mark it as set so Settings shows the field. */
    suspend fun setMapboxToken(token: String) {
        context.dataStore.edit {
            it[MAPBOX_TOKEN] = token
            it[MAPBOX_TOKEN_SET] = true
        }
    }

    /** Clear the token — next Satellite tap will show the setup dialog again. */
    suspend fun clearMapboxToken() {
        context.dataStore.edit {
            it[MAPBOX_TOKEN] = ""
            it[MAPBOX_TOKEN_SET] = false
        }
    }
}
