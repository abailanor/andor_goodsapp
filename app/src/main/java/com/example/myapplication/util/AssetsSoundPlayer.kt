// AssetsSoundPlayer.kt
package com.example.myapplication.util

import android.content.Context
import android.media.MediaPlayer
import android.preference.PreferenceManager
import kotlinx.coroutines.*
import java.io.IOException

class AssetsSoundPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private val soundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var soundEnabled = true // 添加这个开关

    init {
        // 从 SharedPreferences 加载设置
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        soundEnabled = prefs.getBoolean("sound_enabled", true)
    }

    fun setSoundEnabled() {
        soundEnabled = !soundEnabled

        // 保存到 SharedPreferences
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean("sound_enabled", soundEnabled).apply()
    }
    fun returnstat(): Boolean {
        return soundEnabled
    }

    /**
     * 播放 assets 目录下的声音文件
     * @param assetPath 声音文件路径，例如："Already in list.wav"
     * @param onStart 开始播放回调（可选）
     * @param onCompletion 播放完成回调（可选）
     * @param onError 错误回调（可选）
     */
    fun playAssetSound(
        assetPath: String,
        onStart: (() -> Unit)? = null,
        onCompletion: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if(soundEnabled) {
            // 确保在主线程执行回调
            val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

            soundScope.launch {
                try {
                    // 停止当前播放
                    stop()

                    // 创建 MediaPlayer
                    val player = MediaPlayer().apply {
                        // 从 assets 加载音频文件
                        val assetFileDescriptor = context.assets.openFd(assetPath)
                        setDataSource(
                            assetFileDescriptor.fileDescriptor,
                            assetFileDescriptor.startOffset,
                            assetFileDescriptor.length
                        )
                        assetFileDescriptor.close()

                        // 准备播放
                        prepare()

                        // 设置监听器
                        setOnCompletionListener { mp ->
                            mp.release()
                            mediaPlayer = null
                            mainScope.launch {
                                onCompletion?.invoke()
                            }
                        }

                        setOnErrorListener { mp, what, extra ->
                            mainScope.launch {
                                onError?.invoke("播放错误: what=$what, extra=$extra")
                            }
                            mp.release()
                            mediaPlayer = null
                            true
                        }
                    }

                    mediaPlayer = player

                    // 播放声音
                    player.start()
                    mainScope.launch {
                        onStart?.invoke()
                    }

                } catch (e: IOException) {
                    mainScope.launch {
                        onError?.invoke("文件不存在或无法打开: $assetPath")
                    }
                } catch (e: Exception) {
                    mainScope.launch {
                        onError?.invoke("播放失败: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * 播放特定的业务场景声音
     */
    fun playScanSuccess() {
        playAssetSound("success.wav")
    }

    fun playNotExsist() {
        playAssetSound("ProductNotExsist.wav")
    }

    fun playAlreadyInList() {
        playAssetSound("Already in list.wav")
    }

    fun playError() {
        playAssetSound("error.wav")
    }


    /**
     * 停止播放
     */
    fun stop() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                mp.stop()
            }
            mp.release()
            mediaPlayer = null
        }
    }

    /**
     * 设置音量
     */
    fun setVolume(volume: Float) {
        mediaPlayer?.setVolume(volume, volume)
    }

    /**
     * 检查是否正在播放
     */
    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false

    /**
     * 释放资源
     */
    fun release() {
        stop()
        soundScope.cancel()
    }
}