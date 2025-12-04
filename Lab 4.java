// data/security/KeyStoreManager.kt
package com.yourcompany.healthapp.data.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * KeyStoreManager is responsible for securely generating and storing
 * a database passphrase (or any other secret) using Android's
 * hardware-backed Keystore via EncryptedSharedPreferences.
 *
 * This avoids hardcoding secrets in source code or resources.
 */
class KeyStoreManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "secure_prefs"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
    }

    // MasterKey will be backed by Android Keystore, using AES-GCM.
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    // EncryptedSharedPreferences ensures values are encrypted at rest.
    private val securePrefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Gets an existing DB passphrase, or generates and stores a new one.
     * This passphrase will later be used with SQLCipher / SupportFactory
     * to encrypt the Room database.
     */
    fun getOrCreateDbPassphrase(): String {
        val existing = securePrefs.getString(KEY_DB_PASSPHRASE, null)
        if (existing != null) return existing

        // For demo we use a random UUID; in production you may want
        // stronger randomness or binary key material.
        val newKey = java.util.UUID.randomUUID().toString()
        securePrefs.edit().putString(KEY_DB_PASSPHRASE, newKey).apply()
        return newKey
    }
}

// data/local/PatientDatabase.kt
package com.yourcompany.healthapp.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.yourcompany.healthapp.data.security.KeyStoreManager
// import net.sqlcipher.database.SQLiteDatabase
// import net.sqlcipher.database.SupportFactory

/**
 * Encrypted Room database:
 * - Uses SQLCipher SupportFactory with a passphrase stored in Keystore
 * - Stores patients, appointments, EHR summaries and vital signs
 */
@Database(
    entities = [
        PatientEntity::class,
        AppointmentEntity::class,
        PatientEhrEntity::class,
        VitalSignEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class PatientDatabase : RoomDatabase() {

    abstract fun patientDao(): PatientDao
    abstract fun appointmentDao(): AppointmentDao
    abstract fun ehrDao(): EhrDao

    companion object {
        @Volatile
        private var INSTANCE: PatientDatabase? = null

        fun getInstance(context: Context): PatientDatabase {
            return INSTANCE ?: synchronized(this) {

                // Get passphrase from secure storage.
                val keyStoreManager = KeyStoreManager(context)
                val passphraseString = keyStoreManager.getOrCreateDbPassphrase()

                // Convert passphrase to SQLCipher-compatible byte array.
                // val passphrase = SQLiteDatabase.getBytes(passphraseString.toCharArray())
                // val factory = SupportFactory(passphrase)

                val instance =
                    Room.databaseBuilder(
                        context.applicationContext,
                        PatientDatabase::class.java,
                        "patient_db"
                    )
                        // Enable destructive migration for lab simplicity.
                        // In production, define explicit migrations.
                        .fallbackToDestructiveMigration()
                        // Attach encrypted helper factory for SQLCipher (uncomment when deps added)
                        // .openHelperFactory(factory)
                        .build()

                INSTANCE = instance
                instance
            }
        }
    }
}

// data/security/SecurePreferences.kt
package com.yourcompany.healthapp.data.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Wrapper around EncryptedSharedPreferences for any other sensitive
 * configuration or tokens you might need to store.
 *
 * NOTE: Do NOT put PHI here unless strictly necessary. Use this mostly
 * for tokens, feature flags, or security settings.
 */
class SecurePreferences(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_app_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getString(key: String): String? = prefs.getString(key, null)

    fun clear() {
        // Useful if you detect a serious security event and want
        // to remove all sensitive local state.
        prefs.edit().clear().apply()
    }
}

// security/InputSanitizer.kt
package com.yourcompany.healthapp.security

/**
 * Very conservative input sanitiser used for high-risk text fields.
 *
 * We remove characters that are commonly used in injection-style attacks
 * when constructing raw queries (quotes, semicolons, comment tokens).
 *
 * NOTE:
 * - We STILL rely primarily on parameterised queries (Room, Retrofit).
 * - This is an additional defence-in-depth example for the lab.
 */
object InputSanitizer {

    // Regex to remove characters we never expect in simple identity fields.
    private val forbiddenPattern = Regex("[\"'`;]+")
    private val sqlCommentPattern = Regex("--|/\\*|\\*/")

    fun sanitizeForSqlLikeFields(raw: String): String {
        // Strip typical SQL control characters
        var cleaned = forbiddenPattern.replace(raw, "")

        // Remove SQL comment markers
        cleaned = sqlCommentPattern.replace(cleaned, "")

        // Trim whitespace at ends
        return cleaned.trim()
    }
}

// ui/registration/RegistrationViewModel.kt (inside submit())
import com.yourcompany.healthapp.security.InputSanitizer

// ...

val sanitizedNhs = InputSanitizer.sanitizeForSqlLikeFields(nhs)
val sanitizedName = InputSanitizer.sanitizeForSqlLikeFields(name)

val patient = Patient(
    nhsNumber = sanitizedNhs.replace(" ", ""),
    fullName = sanitizedName,
    dateOfBirth = dob!!,
    phoneNumber = phone.ifBlank { null },
    email = email.ifBlank { null }
)

// util/SecureLogger.kt
package com.yourcompany.healthapp.util

import android.util.Log
import com.yourcompany.healthapp.BuildConfig

/**
 * Centralised logging wrapper.
 *
 * Rules:
 * - Never log PHI (names, NHS numbers, diagnoses, lab results, etc.).
 * - In release builds (BuildConfig.DEBUG == false), logs are highly restricted.
 * - Use short, generic messages that are still useful for debugging.
 */
object SecureLogger {

    // Tag is generic; we don't leak feature names that might hint at PHI.
    private const val TAG = "HealthApp"

    /**
     * Verbose / debug logging only allowed in debug builds.
     */
    fun d(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, scrub(message))
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        // In release: log only generic message, no stack traces (to avoid leaking internals).
        if (BuildConfig.DEBUG) {
            Log.e(TAG, scrub(message), throwable)
        } else {
            Log.e(TAG, "An error occurred", null)
        }
    }

    /**
     * Very basic "scrub" function; in a real system you might scan for
     * patterns that look like IDs or emails and remove them.
     */
    private fun scrub(message: String): String {
        // For lab purposes, we just truncate very long messages.
        return if (message.length > 200) {
            message.take(200) + "…"
        } else {
            message
        }
    }
}

// app/build.gradle (only relevant snippets with comments)

android {
    buildTypes {
        debug {
            // Debug builds can log and keep symbols for easier debugging.
            minifyEnabled false
        }
        release {
            // Enable code shrinking + obfuscation for asset protection.
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'),
                    'proguard-rules.pro'

            // BuildConfig.DEBUG will be false here, so SecureLogger
            // automatically suppresses detailed logs and stack traces.
        }
    }
}

// data/remote/SecureRetrofit.kt
package com.yourcompany.healthapp.data.remote

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Retrofit client with:
 * - HTTPS enforced by baseUrl (always https)
 * - Certificate pinning to a known server certificate fingerprint
 * - Auth header interceptor from Lab 2
 *
 * This protects against man-in-the-middle attacks even if a
 * device's root trust store is compromised.
 */
object SecureRetrofit {

    // Replace example.com with your real host.
    private const val HOST = "example-mock-api.test"

    // Example SHA-256 pin; replace with real cert pin for production.
    // You can obtain this using tools like openssl or OkHttp’s CertificatePinner docs.
    private val certificatePinner = CertificatePinner.Builder()
        .add(
            HOST,
            "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        )
        .build()

    // Minimal logging in release; verbose in debug.
    private val logging = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .certificatePinner(certificatePinner)
        .addInterceptor(logging)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://$HOST/")   // HTTPS only
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create())
        .build()

    val apiService: ApiService = retrofit.create(ApiService::class.java)
}

// domain/security/SecurityEvent.kt
package com.yourcompany.healthapp.domain.security

/**
 * Events that might be relevant to security monitoring.
 * These are high-level and abstract; they don't contain PHI.
 */
sealed class SecurityEvent {
    object FailedLogin : SecurityEvent()
    object SuspiciousInput : SecurityEvent()
    object BiometricBypassed : SecurityEvent()
    object RootDetected : SecurityEvent()
}

// domain/security/SecurityAction.kt
package com.yourcompany.healthapp.domain.security

/**
 * Possible automated responses the app can take when a threat is detected.
 */
sealed class SecurityAction {
    object None : SecurityAction()

    // Ask user to re-authenticate strongly (e.g., biometric)
    object RequireStrongAuth : SecurityAction()

    // Lock the account and require manual unlock (e.g., via helpdesk)
    object LockAccount : SecurityAction()

    // Clear local sensitive data (tokens, cached data)
    object ClearLocalData : SecurityAction()

    // Show a warning UI, but keep session alive
    data class ShowWarning(val message: String) : SecurityAction()
}

// domain/security/SecurityState.kt
package com.yourcompany.healthapp.domain.security

/**
 * Internal state used by the agent to make decisions.
 * Tracks counters like failed logins and suspicious inputs.
 */
data class SecurityState(
    val failedLoginCount: Int = 0,
    val suspiciousInputCount: Int = 0,
    val rootDetected: Boolean = false,
    val biometricBypassed: Boolean = false
)

// domain/security/SecurityAgent.kt
package com.yourcompany.healthapp.domain.security

/**
 * SecurityAgent simulates simple automated security monitoring:
 * - tracks failed logins
 * - tracks suspicious inputs
 * - reacts strongly if root is detected or biometrics are bypassed
 *
 * This demonstrates the "agentic security simulation" requirement.
 */
class SecurityAgent(
    private val maxFailedLoginsBeforeLock: Int = 3,
    private val maxSuspiciousInputsBeforeWarning: Int = 2
) {

    var state = SecurityState()
        private set

    /**
     * Feed a new event into the agent; it updates state
     * and returns a recommended SecurityAction.
     */
    fun onEvent(event: SecurityEvent): SecurityAction {
        return when (event) {
            SecurityEvent.FailedLogin -> {
                val newCount = state.failedLoginCount + 1
                state = state.copy(failedLoginCount = newCount)
                if (newCount >= maxFailedLoginsBeforeLock) {
                    SecurityAction.LockAccount
                } else {
                    SecurityAction.ShowWarning("Unsuccessful login attempt")
                }
            }

            SecurityEvent.SuspiciousInput -> {
                val newCount = state.suspiciousInputCount + 1
                state = state.copy(suspiciousInputCount = newCount)
                if (newCount >= maxSuspiciousInputsBeforeWarning) {
                    SecurityAction.RequireStrongAuth
                } else {
                    SecurityAction.ShowWarning("Suspicious activity detected")
                }
            }

            SecurityEvent.BiometricBypassed -> {
                state = state.copy(biometricBypassed = true)
                // Strong reaction: clear data and lock account
                SecurityAction.ClearLocalData
            }

            SecurityEvent.RootDetected -> {
                state = state.copy(rootDetected = true)
                // Strong reaction: lock account
                SecurityAction.LockAccount
            }
        }
    }

    /**
     * Reset counters after appropriate remediation.
     */
    fun reset() {
        state = SecurityState()
    }
}

// ui/security/SecuritySimulationViewModel.kt
package com.yourcompany.healthapp.ui.security

import androidx.lifecycle.ViewModel
import com.yourcompany.healthapp.domain.security.*

/**
 * ViewModel that wraps SecurityAgent for a demo screen.
 * This is used just for Lab 4 demonstration; not used in production flows.
 */
class SecuritySimulationViewModel : ViewModel() {

    private val agent = SecurityAgent()

    var lastAction: SecurityAction = SecurityAction.None
        private set

    var state: SecurityState = agent.state
        private set

    fun trigger(event: SecurityEvent) {
        lastAction = agent.onEvent(event)
        state = agent.state
    }

    fun reset() {
        agent.reset()
        state = agent.state
        lastAction = SecurityAction.None
    }
}

// ui/security/SecuritySimulationScreen.kt
package com.yourcompany.healthapp.ui.security

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yourcompany.healthapp.domain.security.*

/**
 * Simple screen that lets you simulate security events and see how
 * the SecurityAgent responds. You can screenshot this as dynamic
 * testing evidence for Lab 4.
 */
@Composable
fun SecuritySimulationScreen(
    viewModel: SecuritySimulationViewModel
) {
    val state = viewModel.state
    val action = viewModel.lastAction

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text("Security Simulation", style = MaterialTheme.typography.headlineMedium)

            Text("Failed logins: ${state.failedLoginCount}")
            Text("Suspicious inputs: ${state.suspiciousInputCount}")
            Text("Root detected: ${state.rootDetected}")
            Text("Biometric bypassed: ${state.biometricBypassed}")

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.trigger(SecurityEvent.FailedLogin) }) {
                    Text("Simulate Failed Login")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.trigger(SecurityEvent.SuspiciousInput) }) {
                    Text("Simulate Suspicious Input")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.trigger(SecurityEvent.RootDetected) }) {
                    Text("Simulate Root Detection")
                }
                Button(onClick = { viewModel.trigger(SecurityEvent.BiometricBypassed) }) {
                    Text("Simulate Biometric Bypass")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("Last action:", style = MaterialTheme.typography.titleMedium)
            Text(
                text = when (action) {
                    is SecurityAction.None -> "No action"
                    is SecurityAction.RequireStrongAuth -> "Require strong authentication"
                    is SecurityAction.LockAccount -> "Lock account"
                    is SecurityAction.ClearLocalData -> "Clear local data"
                    is SecurityAction.ShowWarning -> "Show warning: ${action.message}"
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { viewModel.reset() }) {
                Text("Reset Simulation")
            }
        }
    }
}

// security/InputSanitizerTest.kt
package com.yourcompany.healthapp.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InputSanitizerTest {

    @Test
    fun `removes dangerous characters`() {
        val raw = "O'Hara; DROP TABLE patients; --"
        val sanitized = InputSanitizer.sanitizeForSqlLikeFields(raw)
        // Expect no quotes, semicolons or SQL comment markers.
        assertFalse(sanitized.contains("'"))
        assertFalse(sanitized.contains(";"))
        assertFalse(sanitized.contains("--"))
    }

    @Test
    fun `keeps normal characters`() {
        val raw = "John Smith 123"
        val sanitized = InputSanitizer.sanitizeForSqlLikeFields(raw)
        assertEquals("John Smith 123", sanitized)
    }
}

// security/SecurityAgentTest.kt
package com.yourcompany.healthapp.security

import com.yourcompany.healthapp.domain.security.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAgentTest {

    @Test
    fun `locks account after too many failed logins`() {
        val agent = SecurityAgent(maxFailedLoginsBeforeLock = 3)

        // First two failures -> warnings
        var action = agent.onEvent(SecurityEvent.FailedLogin)
        assertTrue(action is SecurityAction.ShowWarning)

        action = agent.onEvent(SecurityEvent.FailedLogin)
        assertTrue(action is SecurityAction.ShowWarning)

        // Third failure -> lock account
        action = agent.onEvent(SecurityEvent.FailedLogin)
        assertTrue(action is SecurityAction.LockAccount)
        assertEquals(3, agent.state.failedLoginCount)
    }

    @Test
    fun `biometric bypass triggers clear local data`() {
        val agent = SecurityAgent()
        val action = agent.onEvent(SecurityEvent.BiometricBypassed)
        assertTrue(action is SecurityAction.ClearLocalData)
    }
}
