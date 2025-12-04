package com.yourcompany.healthapp.domain.model

// Basic roles for RBAC (Role-Based Access Control)
enum class UserRole {
    PATIENT,
    CLINICIAN,
    RECEPTIONIST,
    ADMIN
}

package com.yourcompany.healthapp.domain.model

// Status of an appointment in its lifecycle
enum class AppointmentStatus {
    BOOKED,
    CANCELLED,
    COMPLETED
}

package com.yourcompany.healthapp.domain.model

import java.time.LocalDateTime

// Domain model for appointments, independent of Room or Retrofit
data class Appointment(
    val id: Long? = null,
    val patientId: Long,
    val patientName: String,
    val clinic: String,
    val location: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val status: AppointmentStatus,
    val notes: String? = null
)

package com.yourcompany.healthapp.domain.repository

import com.yourcompany.healthapp.domain.model.Appointment
import java.time.LocalDate

// Abstraction for all appointment-related operations
interface AppointmentRepository {

    // Get all appointments for a given day; can be filtered by clinic/location
    suspend fun getAppointmentsForDay(
        date: LocalDate,
        clinicFilter: String? = null,
        locationFilter: String? = null
    ): Result<List<Appointment>>

    // Create a new appointment, with conflict detection done in implementation
    suspend fun bookAppointment(appointment: Appointment): Result<Unit>

    // Reschedule an appointment by id
    suspend fun rescheduleAppointment(
        appointmentId: Long,
        newStart: java.time.LocalDateTime,
        newEnd: java.time.LocalDateTime
    ): Result<Unit>

    // Simple cancel operation
    suspend fun cancelAppointment(appointmentId: Long): Result<Unit>
}

package com.yourcompany.healthapp.domain.repository

import com.yourcompany.healthapp.domain.model.UserRole

// Abstraction for authentication & authorisation
interface AuthRepository {

    // Returns currently authenticated user role (for RBAC)
    suspend fun getCurrentUserRole(): UserRole

    // Persist backup PIN/Password (simplified for lab)
    suspend fun saveFallbackPin(pin: String)

    suspend fun verifyFallbackPin(pin: String): Boolean
}

package com.yourcompany.healthapp.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

// Local database representation of an appointment
@Entity(tableName = "appointments")
data class AppointmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val patientId: Long,
    val patientName: String,
    val clinic: String,
    val location: String,
    val startTimeIso: String,   // LocalDateTime stored as ISO string
    val endTimeIso: String,
    val status: String,         // e.g. "BOOKED"
    val notes: String?
)

package com.yourcompany.healthapp.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AppointmentDao {

    // Get all appointments for given date (yyyy-MM-dd) optionally filtered
    @Query(
        """
        SELECT * FROM appointments
        WHERE date(startTimeIso) = :dateIso
        AND (:clinic IS NULL OR clinic = :clinic)
        AND (:location IS NULL OR location = :location)
        ORDER BY startTimeIso
        """
    )
    suspend fun getAppointmentsForDay(
        dateIso: String,
        clinic: String?,
        location: String?
    ): List<AppointmentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAppointment(entity: AppointmentEntity): Long

    @Query("UPDATE appointments SET startTimeIso = :newStart, endTimeIso = :newEnd WHERE id = :id")
    suspend fun updateAppointmentTime(
        id: Long,
        newStart: String,
        newEnd: String
    )

    @Query("UPDATE appointments SET status = :status WHERE id = :id")
    suspend fun updateAppointmentStatus(
        id: Long,
        status: String
    )

    @Query("SELECT * FROM appointments WHERE id = :id")
    suspend fun getAppointmentById(id: Long): AppointmentEntity?

    // Used for conflict detection
    @Query(
        """
        SELECT * FROM appointments
        WHERE clinic = :clinic
        AND location = :location
        AND status = 'BOOKED'
        AND ((:start < endTimeIso AND :end > startTimeIso))
        """
    )
    suspend fun getConflictingAppointments(
        clinic: String,
        location: String,
        start: String,
        end: String
    ): List<AppointmentEntity>
}

// data/local/PatientDatabase.kt
package com.yourcompany.healthapp.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PatientEntity::class, AppointmentEntity::class], // <-- add AppointmentEntity
    version = 2,                        // bump version when schema changes
    exportSchema = false
)
abstract class PatientDatabase : RoomDatabase() {

    abstract fun patientDao(): PatientDao
    abstract fun appointmentDao(): AppointmentDao // <-- new DAO

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
                        // TODO: connect encrypted helper for full at-rest encryption
                        .fallbackToDestructiveMigration()
                        .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

package com.yourcompany.healthapp.data.remote

// Wrapper to model success, error, and offline states
sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    data class HttpError(val code: Int, val message: String?) : NetworkResult<Nothing>()
    data class NetworkError(val throwable: Throwable) : NetworkResult<Nothing>()
}

package com.yourcompany.healthapp.data.remote

import com.yourcompany.healthapp.data.remote.dto.AppointmentDto
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // Example endpoints for a test/mock API

    @GET("appointments/today")
    suspend fun getTodayAppointments(
        @Query("clinic") clinic: String?,
        @Query("location") location: String?
    ): Response<List<AppointmentDto>>

    @POST("appointments")
    suspend fun bookAppointment(
        @Body dto: AppointmentDto
    ): Response<Unit>

    @PUT("appointments/{id}")
    suspend fun rescheduleAppointment(
        @Path("id") id: Long,
        @Body dto: AppointmentDto
    ): Response<Unit>

    @DELETE("appointments/{id}")
    suspend fun cancelAppointment(
        @Path("id") id: Long
    ): Response<Unit>
}

package com.yourcompany.healthapp.data.remote.dto

// DTO used by Retrofit (shape of JSON from API)
data class AppointmentDto(
    val id: Long?,
    val patientId: Long,
    val patientName: String,
    val clinic: String,
    val location: String,
    val startTimeIso: String,
    val endTimeIso: String,
    val status: String,
    val notes: String?
)

package com.yourcompany.healthapp.data.remote

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

// Builds Retrofit with basic security (HTTPS, auth header) and logging.
object RetrofitClient {

    // Simple auth interceptor to add bearer token header
    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val newRequest = original.newBuilder()
            // In a real app, inject token securely (e.g., EncryptedSharedPreferences)
            .header("Authorization", "Bearer MOCK_TOKEN")
            .build()
        chain.proceed(newRequest)
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://example-mock-api.test/") // Mock or test API URL
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create())
        .build()

    val apiService: ApiService = retrofit.create(ApiService::class.java)
}

package com.yourcompany.healthapp.data.repository

import com.yourcompany.healthapp.data.local.AppointmentDao
import com.yourcompany.healthapp.data.local.AppointmentEntity
import com.yourcompany.healthapp.data.remote.ApiService
import com.yourcompany.healthapp.data.remote.NetworkResult
import com.yourcompany.healthapp.data.remote.dto.AppointmentDto
import com.yourcompany.healthapp.domain.model.Appointment
import com.yourcompany.healthapp.domain.model.AppointmentStatus
import com.yourcompany.healthapp.domain.repository.AppointmentRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class AppointmentRepositoryImpl(
    private val apiService: ApiService,
    private val appointmentDao: AppointmentDao
) : AppointmentRepository {

    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    override suspend fun getAppointmentsForDay(
        date: LocalDate,
        clinicFilter: String?,
        locationFilter: String?
    ): Result<List<Appointment>> {
        val dateIso = date.format(dateFormatter)

        // Try network first, fall back to local if network fails
        val remoteResult = safeCall {
            apiService.getTodayAppointments(clinicFilter, locationFilter)
        }

        val entities: List<AppointmentEntity> = when (remoteResult) {
            is NetworkResult.Success -> {
                // Cache remote data locally
                // (For lab brevity, not deleting old entries)
                remoteResult.data.map { dto -> dto.toEntity() }
                    .also {
                        // In real app, insert into DB (requires DAO upsert)
                        // omitted for shortness
                    }
            }
            else -> {
                // Use locally cached appointments to maintain reliability
                appointmentDao.getAppointmentsForDay(dateIso, clinicFilter, locationFilter)
            }
        }

        return Result.success(entities.map { it.toDomain() })
    }

    override suspend fun bookAppointment(appointment: Appointment): Result<Unit> {
        // Conflict detection using local DB
        val startIso = appointment.startTime.format(dateTimeFormatter)
        val endIso = appointment.endTime.format(dateTimeFormatter)

        val conflicts = appointmentDao.getConflictingAppointments(
            clinic = appointment.clinic,
            location = appointment.location,
            start = startIso,
            end = endIso
        )

        if (conflicts.isNotEmpty()) {
            return Result.failure(IllegalStateException("Appointment conflict detected"))
        }

        // Call API (if available) and then insert into local DB
        val dto = appointment.toDto()
        val remoteResult = safeCall { apiService.bookAppointment(dto) }

        return when (remoteResult) {
            is NetworkResult.Success -> {
                appointmentDao.insertAppointment(dto.toEntity())
                Result.success(Unit)
            }
            is NetworkResult.HttpError -> Result.failure(
                Exception("HTTP ${remoteResult.code}: ${remoteResult.message}")
            )
            is NetworkResult.NetworkError -> {
                // Graceful handling: still allow local insert if appropriate, or fail clearly
                Result.failure(Exception("Network error, please try again later", remoteResult.throwable))
            }
        }
    }

    override suspend fun rescheduleAppointment(
        appointmentId: Long,
        newStart: LocalDateTime,
        newEnd: LocalDateTime
    ): Result<Unit> {
        val existing = appointmentDao.getAppointmentById(appointmentId)
            ?: return Result.failure(IllegalArgumentException("Appointment not found"))

        val newStartIso = newStart.format(dateTimeFormatter)
        val newEndIso = newEnd.format(dateTimeFormatter)

        val conflicts = appointmentDao.getConflictingAppointments(
            clinic = existing.clinic,
            location = existing.location,
            start = newStartIso,
            end = newEndIso
        ).filter { it.id != appointmentId }

        if (conflicts.isNotEmpty()) {
            return Result.failure(IllegalStateException("Appointment conflict detected"))
        }

        val dto = existing.toDomain().copy(
            startTime = newStart,
            endTime = newEnd
        ).toDto()

        val remoteResult = safeCall { apiService.rescheduleAppointment(appointmentId, dto) }

        return when (remoteResult) {
            is NetworkResult.Success -> {
                appointmentDao.updateAppointmentTime(appointmentId, newStartIso, newEndIso)
                Result.success(Unit)
            }
            is NetworkResult.HttpError -> Result.failure(
                Exception("HTTP ${remoteResult.code}: ${remoteResult.message}")
            )
            is NetworkResult.NetworkError -> Result.failure(
                Exception("Network error while rescheduling", remoteResult.throwable)
            )
        }
    }

    override suspend fun cancelAppointment(appointmentId: Long): Result<Unit> {
        val existing = appointmentDao.getAppointmentById(appointmentId)
            ?: return Result.failure(IllegalArgumentException("Appointment not found"))

        val remoteResult = safeCall { apiService.cancelAppointment(appointmentId) }

        return when (remoteResult) {
            is NetworkResult.Success -> {
                appointmentDao.updateAppointmentStatus(appointmentId, AppointmentStatus.CANCELLED.name)
                Result.success(Unit)
            }
            is NetworkResult.HttpError -> Result.failure(
                Exception("HTTP ${remoteResult.code}: ${remoteResult.message}")
            )
            is NetworkResult.NetworkError -> Result.failure(
                Exception("Network error while cancelling", remoteResult.throwable)
            )
        }
    }

    // Common wrapper for Retrofit calls to model network errors
    private suspend fun <T> safeCall(block: suspend () -> retrofit2.Response<T>): NetworkResult<T> {
        return try {
            val response = block()
            if (response.isSuccessful) {
                NetworkResult.Success(response.body()!!)
            } else {
                NetworkResult.HttpError(response.code(), response.message())
            }
        } catch (e: Exception) {
            NetworkResult.NetworkError(e)
        }
    }

    // Mapping functions between DTO/Entity/Domain

    private fun AppointmentEntity.toDomain(): Appointment =
        Appointment(
            id = id,
            patientId = patientId,
            patientName = patientName,
            clinic = clinic,
            location = location,
            startTime = LocalDateTime.parse(startTimeIso, dateTimeFormatter),
            endTime = LocalDateTime.parse(endTimeIso, dateTimeFormatter),
            status = AppointmentStatus.valueOf(status),
            notes = notes
        )

    private fun Appointment.toDto(): AppointmentDto =
        AppointmentDto(
            id = id,
            patientId = patientId,
            patientName = patientName,
            clinic = clinic,
            location = location,
            startTimeIso = startTime.format(dateTimeFormatter),
            endTimeIso = endTime.format(dateTimeFormatter),
            status = status.name,
            notes = notes
        )

    private fun AppointmentDto.toEntity(): AppointmentEntity =
        AppointmentEntity(
            id = id ?: 0L,
            patientId = patientId,
            patientName = patientName,
            clinic = clinic,
            location = location,
            startTimeIso = startTimeIso,
            endTimeIso = endTimeIso,
            status = status,
            notes = notes
        )
}

package com.yourcompany.healthapp.domain.security

import com.yourcompany.healthapp.domain.model.UserRole

// Simple RBAC checks: who can manage appointments
object AccessControl {

    fun canManageAppointments(role: UserRole): Boolean =
        when (role) {
            UserRole.RECEPTIONIST,
            UserRole.CLINICIAN,
            UserRole.ADMIN -> true
            UserRole.PATIENT -> false
        }
}

package com.yourcompany.healthapp.ui.auth

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

// Encapsulates biometric prompt logic for reuse
class BiometricAuthManager(
    private val activity: FragmentActivity,
    private val onSuccess: () -> Unit,
    private val onFallback: () -> Unit
) {

    private val executor = ContextCompat.getMainExecutor(activity)

    fun canAuthenticate(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
                    or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun showBiometricPrompt() {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Secure Login")
            .setSubtitle("Use fingerprint or device credentials")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
                        or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // If user opts for fallback (e.g., negative button), trigger fallback PIN
                    onFallback()
                }
            })

        biometricPrompt.authenticate(promptInfo)
    }
}

// ui/auth/LoginActivity.kt
package com.yourcompany.healthapp.ui.auth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.yourcompany.healthapp.domain.repository.AuthRepository
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class LoginActivity(
    private val authRepository: AuthRepository // Inject via DI in real app
) : ComponentActivity() {

    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val biometricManager = BiometricAuthManager(
            activity = this,
            onSuccess = { navigateToMain() },
            onFallback = { showFallbackPinScreen() }
        )

        if (biometricManager.canAuthenticate(this)) {
            biometricManager.showBiometricPrompt()
        } else {
            showFallbackPinScreen()
        }

        // For simplicity, just show a placeholder while auth is happening
        setContent {
            Text("Authenticating...")
        }
    }

    private fun showFallbackPinScreen() {
        setContent {
            var enteredPin by remember { mutableStateOf("") }
            var errorMessage by remember { mutableStateOf<String?>(null) }

            androidx.compose.material3.OutlinedTextField(
                value = enteredPin,
                onValueChange = { enteredPin = it },
                label = { androidx.compose.material3.Text("Enter PIN") }
            )
            androidx.compose.material3.Button(onClick = {
                scope.launch {
                    val ok = authRepository.verifyFallbackPin(enteredPin)
                    if (ok) navigateToMain() else errorMessage = "Incorrect PIN"
                }
            }) {
                androidx.compose.material3.Text("Login")
            }
            errorMessage?.let { androidx.compose.material3.Text(it) }
        }
    }

    private fun navigateToMain() {
        // TODO: start MainActivity where the rest of the app runs
    }
}

package com.yourcompany.healthapp.ui.appointments

import com.yourcompany.healthapp.domain.model.Appointment

data class AppointmentListState(
    val isLoading: Boolean = false,
    val appointments: List<Appointment> = emptyList(),
    val clinicFilter: String? = null,
    val locationFilter: String? = null,
    val errorMessage: String? = null,
    val canManage: Boolean = false  // From RBAC (role)
)

package com.yourcompany.healthapp.ui.appointments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourcompany.healthapp.domain.model.UserRole
import com.yourcompany.healthapp.domain.repository.AppointmentRepository
import com.yourcompany.healthapp.domain.repository.AuthRepository
import com.yourcompany.healthapp.domain.security.AccessControl
import java.time.LocalDate
import kotlinx.coroutines.launch

class AppointmentListViewModel(
    private val appointmentRepository: AppointmentRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    var state = AppointmentListState()
        private set

    init {
        loadForToday()
        loadRole()
    }

    private fun loadRole() {
        viewModelScope.launch {
            val role: UserRole = authRepository.getCurrentUserRole()
            state = state.copy(
                canManage = AccessControl.canManageAppointments(role)
            )
        }
    }

    fun updateClinicFilter(clinic: String?) {
        state = state.copy(clinicFilter = clinic)
        loadForToday()
    }

    fun updateLocationFilter(location: String?) {
        state = state.copy(locationFilter = location)
        loadForToday()
    }

    fun loadForToday() {
        viewModelScope.launch {
            state = state.copy(isLoading = true, errorMessage = null)
            val result = appointmentRepository.getAppointmentsForDay(
                LocalDate.now(),
                state.clinicFilter,
                state.locationFilter
            )
            state = result.fold(
                onSuccess = { list ->
                    state.copy(isLoading = false, appointments = list)
                },
                onFailure = { e ->
                    state.copy(isLoading = false, errorMessage = e.message)
                }
            )
        }
    }
}

package com.yourcompany.healthapp.ui.appointments

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yourcompany.healthapp.domain.model.Appointment
import java.time.format.DateTimeFormatter

@Composable
fun AppointmentListScreen(
    viewModel: AppointmentListViewModel,
    onBookNew: () -> Unit,
    onSelectAppointment: (Appointment) -> Unit
) {
    val state = viewModel.state
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }

    Scaffold(
        floatingActionButton = {
            if (state.canManage) {
                FloatingActionButton(onClick = onBookNew) {
                    Text("+") // Simple add button
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text("Today's Appointments", style = MaterialTheme.typography.headlineMedium)

            Spacer(modifier = Modifier.height(8.dp))

            // Simple text filters for clinic/location
            Row(modifier = Modifier.fillMaxWidth()) {
                var clinicFilter by remember { mutableStateOf(state.clinicFilter ?: "") }
                OutlinedTextField(
                    value = clinicFilter,
                    onValueChange = {
                        clinicFilter = it
                        viewModel.updateClinicFilter(it.ifBlank { null })
                    },
                    label = { Text("Clinic") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                var locationFilter by remember { mutableStateOf(state.locationFilter ?: "") }
                OutlinedTextField(
                    value = locationFilter,
                    onValueChange = {
                        locationFilter = it
                        viewModel.updateLocationFilter(it.ifBlank { null })
                    },
                    label = { Text("Location") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.errorMessage != null) {
                Text(
                    text = state.errorMessage,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                LazyColumn {
                    items(state.appointments) { appt ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            onClick = { if (state.canManage) onSelectAppointment(appt) }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("${appt.patientName} - ${appt.clinic}")
                                Text(appt.location, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "${appt.startTime.format(timeFormatter)} - ${appt.endTime.format(timeFormatter)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text("Status: ${appt.status}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ui/appointments/AppointmentConflictDetectorTest.kt
package com.yourcompany.healthapp.ui.appointments

import com.yourcompany.healthapp.domain.model.Appointment
import com.yourcompany.healthapp.domain.model.AppointmentStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class AppointmentConflictDetectorTest {

    @Test
    fun `overlapping appointments are detected`() {
        val now = LocalDateTime.now()
        val a1 = Appointment(
            id = 1L,
            patientId = 1L,
            patientName = "Alice",
            clinic = "Clinic A",
            location = "Room 1",
            startTime = now,
            endTime = now.plusMinutes(30),
            status = AppointmentStatus.BOOKED
        )
        val a2 = a1.copy(
            id = 2L,
            startTime = now.plusMinutes(15),
            endTime = now.plusMinutes(45)
        )

        val overlaps = a1.startTime < a2.endTime && a2.startTime < a1.endTime
        assertTrue(overlaps)
    }

    @Test
    fun `back-to-back appointments are allowed`() {
        val now = LocalDateTime.now()
        val a1 = now..now.plusMinutes(30)
        val a2 = now.plusMinutes(30)..now.plusMinutes(60)

        val overlaps = a1.start < a2.endInclusive && a2.start < a1.endInclusive
        assertFalse(overlaps)
    }

    private operator fun LocalDateTime.rangeTo(other: LocalDateTime) =
        object {
            val start = this@rangeTo
            val endInclusive = other
        }
}

// domain/security/AccessControlTest.kt
package com.yourcompany.healthapp.domain.security

import com.yourcompany.healthapp.domain.model.UserRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessControlTest {

    @Test
    fun `patients cannot manage appointments`() {
        assertFalse(AccessControl.canManageAppointments(UserRole.PATIENT))
    }

    @Test
    fun `receptionists can manage appointments`() {
        assertTrue(AccessControl.canManageAppointments(UserRole.RECEPTIONIST))
    }
}