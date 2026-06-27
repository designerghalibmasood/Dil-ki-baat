package com.example.ui.screens

import android.app.Application
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.models.Recording
import com.example.services.AppDatabase
import com.example.services.AudioService
import com.example.utils.DateTimeUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class VoiceDiaryViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val database = AppDatabase.getDatabase(context)
    private val recordingDao = database.recordingDao()
    private val audioService = AudioService(context)

    // Flow for all recordings, ordered by newest first
    val recordings: StateFlow<List<Recording>> = recordingDao.getAllRecordings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Recording State
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSeconds = MutableStateFlow(0)
    val recordingDurationSeconds: StateFlow<Int> = _recordingDurationSeconds.asStateFlow()

    private var timerJob: Job? = null

    // Playback State
    private val _activePlayingFile = MutableStateFlow<String?>(null)
    val activePlayingFile: StateFlow<String?> = _activePlayingFile.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    // Status Message / Error state
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        // Sync local directory files with Room DB to prevent ghost entries
        syncRecordingsWithFileSystem()
    }

    private fun syncRecordingsWithFileSystem() {
        viewModelScope.launch {
            try {
                val filesDir = context.filesDir
                val audioFiles = filesDir.listFiles { _, name ->
                    name.startsWith("Recording_") && name.endsWith(".m4a")
                } ?: emptyArray()

                // 1. Check database records vs files
                val dbRecordings = recordingDao.getAllRecordings().stateIn(viewModelScope).value
                for (dbRecord in dbRecordings) {
                    val fileExists = File(dbRecord.filePath).exists()
                    if (!fileExists) {
                        recordingDao.deleteRecording(dbRecord)
                    }
                }

                // 2. Check files vs database records
                for (file in audioFiles) {
                    val recordExists = recordingDao.getRecordingById(file.name) != null
                    if (!recordExists) {
                        // Recreate entry from file details
                        val timestamp = parseTimestampFromFileName(file.name) ?: file.lastModified()
                        val duration = getAudioFileDuration(file)
                        
                        val recording = Recording(
                            id = file.name,
                            filePath = file.absolutePath,
                            fileName = file.name,
                            formattedDate = DateTimeUtils.formatDisplayDate(timestamp),
                            formattedTime = DateTimeUtils.formatDisplayTime(timestamp),
                            durationMs = duration,
                            createdAt = timestamp
                        )
                        recordingDao.insertRecording(recording)
                    }
                }
            } catch (e: Exception) {
                Log.e("VoiceDiaryViewModel", "Error during file synchronization", e)
            }
        }
    }

    private fun parseTimestampFromFileName(fileName: String): Long? {
        return try {
            val cleanName = fileName.removePrefix("Recording_").removeSuffix(".m4a")
            val sdf = java.text.SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", java.util.Locale.US)
            sdf.parse(cleanName)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun getAudioFileDuration(file: File): Long {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            time?.toLong() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            retriever?.release()
        }
    }

    fun startRecording() {
        if (_isRecording.value) return
        
        // Ensure playback is stopped before starting a recording
        stopPlayback()

        val timestamp = System.currentTimeMillis()
        val fileName = DateTimeUtils.generateFileName(timestamp)
        val outputFile = File(context.filesDir, fileName)

        val success = audioService.startRecording(outputFile)
        if (success) {
            _isRecording.value = true
            _recordingDurationSeconds.value = 0
            
            // Start the recording duration timer
            timerJob?.cancel()
            timerJob = viewModelScope.launch {
                while (isActive) {
                    delay(1000)
                    _recordingDurationSeconds.value += 1
                }
            }
        } else {
            showStatus("Failed to start recording")
        }
    }

    fun stopRecording() {
        if (!_isRecording.value) return

        timerJob?.cancel()
        timerJob = null
        _isRecording.value = false

        val durationMs = audioService.stopRecording()
        val durationSeconds = _recordingDurationSeconds.value
        val calculatedDuration = if (durationMs > 0) durationMs else (durationSeconds * 1000L)

        // Find the recording file we just completed
        val filesDir = context.filesDir
        val latestFile = filesDir.listFiles { _, name ->
            name.startsWith("Recording_") && name.endsWith(".m4a")
        }?.maxByOrNull { it.lastModified() }

        if (latestFile != null && latestFile.exists() && calculatedDuration > 100) {
            val timestamp = latestFile.lastModified()
            val recording = Recording(
                id = latestFile.name,
                filePath = latestFile.absolutePath,
                fileName = latestFile.name,
                formattedDate = DateTimeUtils.formatDisplayDate(timestamp),
                formattedTime = DateTimeUtils.formatDisplayTime(timestamp),
                durationMs = calculatedDuration,
                createdAt = timestamp
            )
            viewModelScope.launch {
                recordingDao.insertRecording(recording)
            }
        } else {
            // Delete very short or invalid recordings
            latestFile?.let {
                if (it.exists()) it.delete()
            }
            showStatus("Recording too short")
        }
    }

    fun togglePlayback(recording: Recording) {
        val currentPlaying = _activePlayingFile.value
        if (currentPlaying == recording.filePath) {
            // Is already the active file
            if (_isPlaying.value) {
                audioService.pausePlayback()
                _isPlaying.value = false
                _isPaused.value = true
            } else if (_isPaused.value) {
                audioService.resumePlayback()
                _isPlaying.value = true
                _isPaused.value = false
            } else {
                startPlaybackInternal(recording)
            }
        } else {
            // Playing a different file
            startPlaybackInternal(recording)
        }
    }

    private fun startPlaybackInternal(recording: Recording) {
        _activePlayingFile.value = recording.filePath
        _isPlaying.value = true
        _isPaused.value = false

        audioService.startPlayback(
            filePath = recording.filePath,
            onComplete = {
                _isPlaying.value = false
                _isPaused.value = false
                _activePlayingFile.value = null
            },
            onError = { error ->
                showStatus(error)
                _isPlaying.value = false
                _isPaused.value = false
                _activePlayingFile.value = null
            }
        )
    }

    fun stopPlayback() {
        if (_isPlaying.value || _isPaused.value) {
            audioService.stopPlayback()
            _isPlaying.value = false
            _isPaused.value = false
            _activePlayingFile.value = null
        }
    }

    fun deleteRecording(recording: Recording) {
        viewModelScope.launch {
            // Stop playback if we are deleting the currently playing recording
            if (_activePlayingFile.value == recording.filePath) {
                stopPlayback()
            }

            // Delete file from disk
            val file = File(recording.filePath)
            if (file.exists()) {
                file.delete()
            }

            // Delete metadata from database
            recordingDao.deleteRecording(recording)
            showStatus("Recording deleted")
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    private fun showStatus(message: String) {
        _statusMessage.value = message
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        audioService.release()
    }
}
