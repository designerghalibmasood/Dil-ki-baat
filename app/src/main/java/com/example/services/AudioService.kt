package com.example.services

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

class AudioService(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    
    private var recordingStartTime = 0L
    private var currentRecordingFile: File? = null

    // For playback state callbacks
    private var onPlaybackComplete: (() -> Unit)? = null

    fun startRecording(outputFile: File): Boolean {
        if (mediaRecorder != null) return false
        
        currentRecordingFile = outputFile
        recordingStartTime = System.currentTimeMillis()

        return try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(outputFile.absolutePath)
                
                // Fine-tuning for voice diary clarity
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(96000)
                
                prepare()
                start()
            }
            mediaRecorder = recorder
            true
        } catch (e: Exception) {
            Log.e("AudioService", "startRecording failed", e)
            currentRecordingFile = null
            false
        }
    }

    fun stopRecording(): Long {
        val recorder = mediaRecorder ?: return 0L
        val duration = System.currentTimeMillis() - recordingStartTime
        
        try {
            recorder.stop()
        } catch (e: Exception) {
            Log.e("AudioService", "stopRecording failed during recorder.stop()", e)
            // If stopping failed (e.g. too short), delete empty file
            currentRecordingFile?.let {
                if (it.exists()) {
                    it.delete()
                }
            }
            return 0L
        } finally {
            recorder.release()
            mediaRecorder = null
        }
        
        return if (duration > 0) duration else 0L
    }

    fun isRecording(): Boolean {
        return mediaRecorder != null
    }

    fun startPlayback(
        filePath: String,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        // Stop any active playback first
        stopPlayback()
        
        onPlaybackComplete = onComplete
        
        try {
            val player = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                start()
                setOnCompletionListener {
                    stopPlayback()
                }
            }
            mediaPlayer = player
        } catch (e: IOException) {
            Log.e("AudioService", "startPlayback failed", e)
            onError("Cannot play this recording")
            stopPlayback()
        }
    }

    fun pausePlayback() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            }
        }
    }

    fun resumePlayback() {
        mediaPlayer?.let { player ->
            if (!player.isPlaying) {
                player.start()
            }
        }
    }

    fun stopPlayback() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (e: Exception) {
                Log.e("AudioService", "Error stopping player", e)
            } finally {
                player.release()
            }
        }
        mediaPlayer = null
        onPlaybackComplete?.invoke()
        onPlaybackComplete = null
    }

    fun isPlaying(): Boolean {
        return mediaPlayer?.isPlaying ?: false
    }

    fun release() {
        stopRecording()
        stopPlayback()
    }
}
