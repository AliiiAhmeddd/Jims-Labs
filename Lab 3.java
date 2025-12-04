package com.yourcompany.healthapp.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores summary EHR information per patient:
 * - long-term conditions
 * - allergies
 * - current medications
 *
 * For simplicity, these are stored as text lists (comma- or line-separated).
 * In a real system, you'd likely normalise them into separate tables.
 */
@Entity(tableName = "patient_ehr")
data class PatientEhrEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val patientId: Long,       // Foreign-key-like link to PatientEntity.id
    val conditions: String,    // e.g. "Asthma, Hypertension"
    val allergies: String,     // e.g. "Penicillin, Nuts"
    val medications: String    // e.g. "Drug A 10mg, Drug B 20mg"
)

package com.yourcompany.healthapp.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single set of vital signs recorded for a patient.
 *
 * We also track:
 * - timestampIso: when the vitals were recorded
 * - synced: whether this record has been uploaded to the server (for offline sync)
 */
@Entity(tableName = "vital_signs")
data class VitalSignEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val patientId: Long,
    val timestampIso: String,       // ISO-8601 LocalDateTime string
    val heartRateBpm: Int,          // beats per minute
    val bodyTemperatureC: Double,   // degrees Celsius
    val bloodGlucoseMmolL: Double,  // mmol/L
    val synced: Boolean = false     // false = pending upload, true = synced with server
)

package com.yourcompany.healthapp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * DAO for reading/writing EHR & vital sign information.
 * Includes methods for:
 * - loading EHR summary
 * - inserting/updating EHR
 * - paginated vital sign queries
 * - retrieving unsynced vitals for background sync
 */
@Dao
interface EhrDao {

    // --- EHR summary ---

    @Query("SELECT * FROM patient_ehr WHERE patientId = :patientId LIMIT 1")
    suspend fun getEhrForPatient(patientId: Long): PatientEhrEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEhr(ehr: PatientEhrEntity): Long

    // --- Vital signs with pagination ---

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVitalSign(vital: VitalSignEntity): Long

    /**
     * Returns a page of vital signs for a patient.
     * - LIMIT controls page size
     * - OFFSET = pageIndex * pageSize
     */
    @Query(
        """
        SELECT * FROM vital_signs
        WHERE patientId = :patientId
        ORDER BY timestampIso DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getVitalsForPatientPaged(
        patientId: Long,
        limit: Int,
        offset: Int
    ): List<VitalSignEntity>

    // --- Offline sync support ---

    /**
     * Returns all vital sign records that have not yet been synced to the server.
     */
    @Query("SELECT * FROM vital_signs WHERE synced = 0")
    suspend fun getUnsyncedVitals(): List<VitalSignEntity>

    @Update
    suspend fun updateVitalSigns(vitals: List<VitalSignEntity>)
}

// data/local/PatientDao.kt
package com.yourcompany.healthapp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PatientDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPatient(patient: PatientEntity)

    /**
     * Find patient by NHS number (used by barcode scanning).
     * NOTE: No logging of the NHS number to keep PHI out of logs.
     */
    @Query("SELECT * FROM patients WHERE nhsNumber = :nhsNumber LIMIT 1")
    suspend fun findPatientByNhsNumber(nhsNumber: String): PatientEntity?
}

// data/local/PatientDatabase.kt
package com.yourcompany.healthapp.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Main Room database that stores:
 * - Patients
 * - Appointments (from Lab 2)
 * - Patient EHR (conditions, allergies, medications)
 * - Vital signs (lab 3)
 */
@Database(
    entities = [
        PatientEntity::class,
        AppointmentEntity::class,
        PatientEhrEntity::class,
        VitalSignEntity::class
    ],
    version = 3,              // Bumped version for new tables
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
                val instance =
                    Room.databaseBuilder(
                        context.applicationContext,
                        PatientDatabase::class.java,
                        "patient_db"
                    )
                        // For this lab, destructive migration is acceptable.
                        // In production, create migration scripts instead.
                        .fallbackToDestructiveMigration()
                        // TODO: Configure encrypted helper factory here.
                        .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

package com.yourcompany.healthapp.domain.model

import java.time.LocalDateTime

/**
 * Domain model for vital signs, independent from Room.
 */
data class VitalSign(
    val id: Long? = null,
    val patientId: Long,
    val timestamp: LocalDateTime,
    val heartRateBpm: Int,
    val bodyTemperatureC: Double,
    val bloodGlucoseMmolL: Double,
    val synced: Boolean = false
)

package com.yourcompany.healthapp.domain.model

/**
 * Aggregated summary of a patient's clinical record:
 * - conditions, allergies, medications (list form)
 * - latest recorded vital signs (optional)
 */
data class PatientSummary(
    val patient: Patient,             // Base patient info from Lab 1
    val conditions: List<String>,
    val allergies: List<String>,
    val medications: List<String>,
    val latestVitalSign: VitalSign?   // Null if no vitals recorded yet
)

package com.yourcompany.healthapp.domain.repository

import com.yourcompany.healthapp.domain.model.PatientSummary
import com.yourcompany.healthapp.domain.model.VitalSign

/**
 * Abstraction for EHR-related operations:
 * - loading summary
 * - recording vitals
 * - paginated history
 */
interface EhrRepository {

    suspend fun getPatientSummary(patientId: Long): Result<PatientSummary>

    suspend fun savePatientEhr(
        patientId: Long,
        conditions: List<String>,
        allergies: List<String>,
        medications: List<String>
    ): Result<Unit>

    suspend fun recordVitalSign(vitalSign: VitalSign): Result<Unit>

    suspend fun getVitalsPaged(
        patientId: Long,
        pageIndex: Int,
        pageSize: Int
    ): Result<List<VitalSign>>
}

package com.yourcompany.healthapp.data.repository

import com.yourcompany.healthapp.data.local.EhrDao
import com.yourcompany.healthapp.data.local.PatientDao
import com.yourcompany.healthapp.data.local.PatientEhrEntity
import com.yourcompany.healthapp.data.local.VitalSignEntity
import com.yourcompany.healthapp.domain.model.Patient
import com.yourcompany.healthapp.domain.model.PatientSummary
import com.yourcompany.healthapp.domain.model.VitalSign
import com.yourcompany.healthapp.domain.repository.EhrRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Concrete EhrRepository implementation using Room DAOs.
 * Converts between Entity and Domain models.
 */
class EhrRepositoryImpl(
    private val ehrDao: EhrDao,
    private val patientDao: PatientDao
) : EhrRepository {

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override suspend fun getPatientSummary(patientId: Long): Result<PatientSummary> {
        return try {
            // Load patient core info
            val patientEntity = patientDao.getById(patientId)
                ?: return Result.failure(IllegalArgumentException("Patient not found"))

            val patient = Patient(
                id = patientEntity.id,
                nhsNumber = patientEntity.nhsNumber,
                fullName = patientEntity.fullName,
                dateOfBirth = LocalDate.parse(patientEntity.dateOfBirth, dateFormatter),
                phoneNumber = patientEntity.phoneNumber,
                email = patientEntity.email
            )

            // Load EHR summary (if missing, treat as empty lists)
            val ehrEntity = ehrDao.getEhrForPatient(patientId)
            val conditions = ehrEntity?.conditions?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
            val allergies = ehrEntity?.allergies?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()
            val medications = ehrEntity?.medications?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: emptyList()

            // Load most recent vital sign (page 0, size 1)
            val vitals = ehrDao.getVitalsForPatientPaged(
                patientId = patientId,
                limit = 1,
                offset = 0
            )
            val latestVital = vitals.firstOrNull()?.toDomain()

            Result.success(
                PatientSummary(
                    patient = patient,
                    conditions = conditions,
                    allergies = allergies,
                    medications = medications,
                    latestVitalSign = latestVital
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun savePatientEhr(
        patientId: Long,
        conditions: List<String>,
        allergies: List<String>,
        medications: List<String>
    ): Result<Unit> {
        return try {
            val entity = PatientEhrEntity(
                patientId = patientId,
                conditions = conditions.joinToString(", "),
                allergies = allergies.joinToString(", "),
                medications = medications.joinToString(", ")
            )
            ehrDao.upsertEhr(entity)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun recordVitalSign(vitalSign: VitalSign): Result<Unit> {
        return try {
            val entity = VitalSignEntity(
                patientId = vitalSign.patientId,
                timestampIso = vitalSign.timestamp.format(dateTimeFormatter),
                heartRateBpm = vitalSign.heartRateBpm,
                bodyTemperatureC = vitalSign.bodyTemperatureC,
                bloodGlucoseMmolL = vitalSign.bloodGlucoseMmolL,
                synced = false // Newly recorded vitals are not yet synced
            )
            ehrDao.insertVitalSign(entity)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getVitalsPaged(
        patientId: Long,
        pageIndex: Int,
        pageSize: Int
    ): Result<List<VitalSign>> {
        return try {
            val offset = pageIndex * pageSize
            val entities = ehrDao.getVitalsForPatientPaged(patientId, pageSize, offset)
            Result.success(entities.map { it.toDomain() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Mapping helpers ---

    private fun VitalSignEntity.toDomain(): VitalSign =
        VitalSign(
            id = id,
            patientId = patientId,
            timestamp = LocalDateTime.parse(timestampIso, dateTimeFormatter),
            heartRateBpm = heartRateBpm,
            bodyTemperatureC = bodyTemperatureC,
            bloodGlucoseMmolL = bloodGlucoseMmolL,
            synced = synced
        )
}

// In PatientDao.kt
@Query("SELECT * FROM patients WHERE id = :id LIMIT 1")
suspend fun getById(id: Long): PatientEntity?

// In PatientDao.kt
@Query("SELECT * FROM patients WHERE id = :id LIMIT 1")
suspend fun getById(id: Long): PatientEntity?

package com.yourcompany.healthapp.ui.ehr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourcompany.healthapp.domain.model.PatientSummary
import com.yourcompany.healthapp.domain.repository.EhrRepository
import kotlinx.coroutines.launch

/**
 * Loads and exposes a PatientSummary for the UI.
 */
class PatientSummaryViewModel(
    private val patientId: Long,
    private val ehrRepository: EhrRepository
) : ViewModel() {

    var state = PatientSummaryState()
        private set

    init {
        loadSummary()
    }

    fun loadSummary() {
        viewModelScope.launch {
            state = state.copy(isLoading = true, errorMessage = null)
            val result = ehrRepository.getPatientSummary(patientId)
            state = result.fold(
                onSuccess = { summary ->
                    state.copy(isLoading = false, summary = summary)
                },
                onFailure = { e ->
                    state.copy(isLoading = false, errorMessage = e.message)
                }
            )
        }
    }
}

/**
 * UI state holder for the patient summary screen.
 */
data class PatientSummaryState(
    val isLoading: Boolean = false,
    val summary: PatientSummary? = null,
    val errorMessage: String? = null
)

package com.yourcompany.healthapp.ui.ehr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter

/**
 * Displays:
 * - patient demographics
 * - conditions, allergies, medications
 * - latest vital signs
 *
 * This screen is read-only; editing can be done via separate form screens.
 */
@Composable
fun PatientSummaryScreen(
    viewModel: PatientSummaryViewModel
) {
    val state = viewModel.state
    val dateFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy")
    val timeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")

    when {
        state.isLoading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        state.errorMessage != null -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp)
            ) {
                Text(
                    text = state.errorMessage,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        state.summary != null -> {
            val summary = state.summary
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Patient details
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(summary.patient.fullName, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "DOB: ${summary.patient.dateOfBirth.format(dateFormatter)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            // No NHS number displayed here to reduce PHI exposure.
                        }
                    }
                }

                // Conditions
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Conditions", style = MaterialTheme.typography.titleMedium)
                            if (summary.conditions.isEmpty()) {
                                Text("No recorded conditions")
                            } else {
                                summary.conditions.forEach {
                                    Text("- $it")
                                }
                            }
                        }
                    }
                }

                // Allergies
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Allergies", style = MaterialTheme.typography.titleMedium)
                            if (summary.allergies.isEmpty()) {
                                Text("No recorded allergies")
                            } else {
                                summary.allergies.forEach {
                                    Text("- $it")
                                }
                            }
                        }
                    }
                }

                // Medications
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Medications", style = MaterialTheme.typography.titleMedium)
                            if (summary.medications.isEmpty()) {
                                Text("No recorded medications")
                            } else {
                                summary.medications.forEach {
                                    Text("- $it")
                                }
                            }
                        }
                    }
                }

                // Latest vitals
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Latest Vital Signs", style = MaterialTheme.typography.titleMedium)
                            val vital = summary.latestVitalSign
                            if (vital == null) {
                                Text("No vitals recorded")
                            } else {
                                Text("Time: ${vital.timestamp.format(timeFormatter)}")
                                Text("Heart Rate: ${vital.heartRateBpm} bpm")
                                Text("Temperature: ${vital.bodyTemperatureC} °C")
                                Text("Blood Glucose: ${vital.bloodGlucoseMmolL} mmol/L")
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.yourcompany.healthapp.ui.vitals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourcompany.healthapp.domain.model.VitalSign
import com.yourcompany.healthapp.domain.repository.EhrRepository
import java.time.LocalDateTime
import kotlinx.coroutines.launch

/**
 * Handles the vital sign entry form:
 * - holds UI state
 * - validates user input
 * - calls EhrRepository to save data
 */
class VitalSignEntryViewModel(
    private val patientId: Long,
    private val ehrRepository: EhrRepository
) : ViewModel() {

    var state = VitalSignEntryState()
        private set

    fun onHeartRateChanged(value: String) {
        state = state.copy(heartRate = value, errorMessage = null, success = false)
    }

    fun onTemperatureChanged(value: String) {
        state = state.copy(temperature = value, errorMessage = null, success = false)
    }

    fun onGlucoseChanged(value: String) {
        state = state.copy(glucose = value, errorMessage = null, success = false)
    }

    fun onSubmit() {
        // Basic input validation
        val hr = state.heartRate.toIntOrNull()
        val temp = state.temperature.toDoubleOrNull()
        val glucose = state.glucose.toDoubleOrNull()

        if (hr == null || temp == null || glucose == null) {
            state = state.copy(errorMessage = "Please enter valid numeric values")
            return
        }

        viewModelScope.launch {
            state = state.copy(isSubmitting = true, errorMessage = null)
            val vital = VitalSign(
                patientId = patientId,
                timestamp = LocalDateTime.now(),
                heartRateBpm = hr,
                bodyTemperatureC = temp,
                bloodGlucoseMmolL = glucose
            )
            val result = ehrRepository.recordVitalSign(vital)
            state = result.fold(
                onSuccess = {
                    state.copy(isSubmitting = false, success = true)
                },
                onFailure = { e ->
                    state.copy(isSubmitting = false, errorMessage = e.message)
                }
            )
        }
    }
}

/**
 * UI state for vital sign entry screen.
 */
data class VitalSignEntryState(
    val heartRate: String = "",
    val temperature: String = "",
    val glucose: String = "",
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val success: Boolean = false
)
package com.yourcompany.healthapp.ui.vitals

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Simple form for recording vital signs:
 * - heart rate
 * - temperature
 * - blood glucose
 *
 * Respects lab requirement: no PHI in notifications.
 * Snackbars and messages are generic and do not include names or NHS numbers.
 */
@Composable
fun VitalSignEntryScreen(
    viewModel: VitalSignEntryViewModel
) {
    val state = viewModel.state
    val snackbarHostState = remember { SnackbarHostState() }

    // React to success/error and show snackbars
    LaunchedEffect(state.success, state.errorMessage) {
        when {
            state.success -> snackbarHostState.showSnackbar("Vital signs saved")
            state.errorMessage != null -> snackbarHostState.showSnackbar("Failed to save vital signs")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start
        ) {
            Text("Record Vital Signs", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = state.heartRate,
                onValueChange = viewModel::onHeartRateChanged,
                label = { Text("Heart Rate (bpm)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = state.temperature,
                onValueChange = viewModel::onTemperatureChanged,
                label = { Text("Body Temperature (°C)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = state.glucose,
                onValueChange = viewModel::onGlucoseChanged,
                label = { Text("Blood Glucose (mmol/L)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { viewModel.onSubmit() },
                enabled = !state.isSubmitting,
                modifier = Modifier.align(Alignment.End)
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 8.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text("Save")
            }
        }
    }
}

package com.yourcompany.healthapp.ui.barcode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourcompany.healthapp.domain.model.Patient
import com.yourcompany.healthapp.domain.repository.PatientRepository
import kotlinx.coroutines.launch

/**
 * Given a scanned NHS number (from barcode),
 * this ViewModel finds the corresponding patient.
 */
class BarcodeScanViewModel(
    private val patientRepository: PatientRepository
) : ViewModel() {

    var state = BarcodeScanState()
        private set

    fun onBarcodeScanned(nhsNumber: String) {
        // For security, we do not log the scanned value anywhere.
        state = state.copy(isLoading = true, errorMessage = null, matchedPatient = null)

        viewModelScope.launch {
            val result = patientRepository.findByNhsNumber(nhsNumber)
            state = result.fold(
                onSuccess = { patient ->
                    state.copy(isLoading = false, matchedPatient = patient)
                },
                onFailure = { e ->
                    state.copy(isLoading = false, errorMessage = e.message ?: "No patient found")
                }
            )
        }
    }
}

data class BarcodeScanState(
    val isLoading: Boolean = false,
    val matchedPatient: Patient? = null,
    val errorMessage: String? = null
)

// domain/repository/PatientRepository.kt
suspend fun findByNhsNumber(nhsNumber: String): Result<Patient>

package com.yourcompany.healthapp.ui.barcode

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * UI shell around barcode scanning.
 * This screen:
 * - shows a "Scan" button to trigger barcode capture
 * - displays matched patient or error
 *
 * The actual camera scanning would be implemented in Activity/Fragment
 * using ML Kit or ZXing, then calling viewModel.onBarcodeScanned(resultNhs).
 */
@Composable
fun BarcodeScanScreen(
    viewModel: BarcodeScanViewModel,
    onNavigateToPatientSummary: (Long) -> Unit
) {
    val state = viewModel.state

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Scan Patient Wristband", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    // TODO: Launch barcode scanner here.
                    // For now, simulate a scan with a hard-coded NHS number:
                    val simulatedNhs = "9434765919"
                    viewModel.onBarcodeScanned(simulatedNhs)
                }
            ) {
                Text("Scan Barcode")
            }

            Spacer(modifier = Modifier.height(24.dp))

            when {
                state.isLoading -> CircularProgressIndicator()

                state.matchedPatient != null -> {
                    val p = state.matchedPatient
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Patient Found:", style = MaterialTheme.typography.titleMedium)
                            Text(p.fullName)
                            // NHS number can be displayed in a clinical context
                            // but we still avoid logging it or showing it in notifications.
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { onNavigateToPatientSummary(p.id!!) }) {
                                Text("View Clinical Record")
                            }
                        }
                    }
                }

                state.errorMessage != null -> {
                    Text(
                        text = state.errorMessage,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

package com.yourcompany.healthapp.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yourcompany.healthapp.data.local.EhrDao
import com.yourcompany.healthapp.data.remote.ApiService
import com.yourcompany.healthapp.data.remote.dto.VitalSignDto // you would define this similar to AppointmentDto

/**
 * Background worker that:
 * - finds unsynced vital sign records
 * - uploads them to the backend
 * - marks them as synced on success
 *
 * IMPORTANT: No PHI is written to logs.
 * Any log messages must be generic (no names, NHS numbers, or raw readings).
 */
class VitalSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val ehrDao: EhrDao,
    private val apiService: ApiService
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val unsynced = ehrDao.getUnsyncedVitals()

            if (unsynced.isEmpty()) {
                // Generic log line (if used): "No records to sync"
                return Result.success()
            }

            // Map entities to DTOs for the API
            val dtos = unsynced.map { entity ->
                VitalSignDto(
                    id = entity.id,
                    patientId = entity.patientId,
                    timestampIso = entity.timestampIso,
                    heartRateBpm = entity.heartRateBpm,
                    bodyTemperatureC = entity.bodyTemperatureC,
                    bloodGlucoseMmolL = entity.bloodGlucoseMmolL
                )
            }

            // Simplified: assume a bulk upload endpoint exists
            val response = apiService.syncVitalSigns(dtos)
            if (response.isSuccessful) {
                // Mark records as synced; we do NOT log any identifying details.
                val updated = unsynced.map { it.copy(synced = true) }
                ehrDao.updateVitalSigns(updated)
                Result.success()
            } else {
                // Avoid including any PHI in error messages or logs.
                Result.retry()
            }
        } catch (e: Exception) {
            // Avoid logging patient details here; just report generic error.
            return Result.retry()
        }
    }
}

// Example in Application or a dedicated sync manager:
val request = androidx.work.PeriodicWorkRequestBuilder<VitalSyncWorker>(
    repeatInterval = java.time.Duration.ofMinutes(30)
).build()

androidx.work.WorkManager
    .getInstance(context)
    .enqueueUniquePeriodicWork(
        "vital-sync",
        androidx.work.ExistingPeriodicWorkPolicy.KEEP,
        request
    )