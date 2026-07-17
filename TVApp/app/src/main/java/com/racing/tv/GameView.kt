package com.racing.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private var gameThread: Thread? = null
    @Volatile private var isRunning = false
    
    // Networking
    private var udpSocket: DatagramSocket? = null
    private val port = 9999
    
    // Input data from controller
    private var inputX = 0f 
    private var inputY = 0f 
    
    // Car state
    private var carX = 0f
    private var carY = 0f
    private var velocity = 0f
    private var angle = 0f 
    private val carWidth = 50f
    private val carHeight = 25f 
    
    // Physics constants
    private val maxSpeed = 22f
    private val acceleration = 0.5f
    private val friction = 0.94f 
    private val steeringSensitivity = 0.7f
    
    // Level & TileMap State
    private var currentLevelIndex = 0
    private var currentLevelMap = LevelManager.levels[0]
    private var tileSize = 0f
    private var mapOffsetX = 0f
    private var mapOffsetY = 0f
    private var hasFinishedLevel = false
    private var levelFinishTimer = 0L
    
    // Assets
    private var carBitmap: Bitmap? = null
    private var grassBitmap: Bitmap? = null
    private var roadBitmap: Bitmap? = null
    
    // Audio
    private var soundPool: SoundPool? = null
    private var engineSoundId = 0
    private var crashSoundId = 0
    private var engineStreamId = 0
    private var isEnginePlaying = false
    
    // Paints
    private val textPaint = Paint().apply { 
        color = Color.WHITE
        textSize = 50f 
        isAntiAlias = true
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }
    private val finishLinePaint = Paint().apply { color = Color.parseColor("#FFD700") } 

    init {
        holder.addCallback(this)
        loadAssets()
        startListeningUDP()
    }
    
    private fun loadAssets() {
        try {
            // Load Bitmaps generated from AI
            carBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.car)
            grassBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.grass)
            roadBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.road)
            
            // Setup Audio
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            
            soundPool = SoundPool.Builder()
                .setMaxStreams(5)
                .setAudioAttributes(audioAttributes)
                .build()
                
            engineSoundId = soundPool?.load(context, R.raw.engine, 1) ?: 0
            crashSoundId = soundPool?.load(context, R.raw.crash, 1) ?: 0
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startListeningUDP() {
        thread {
            try {
                udpSocket = DatagramSocket(port)
                val buffer = ByteArray(256)
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    if (message.startsWith("DATA:")) {
                        val parts = message.substring(5).split(",")
                        if (parts.size >= 2) {
                            inputX = parts[0].toFloatOrNull() ?: 0f
                            inputY = parts[1].toFloatOrNull() ?: 0f
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isRunning = true
        gameThread = Thread(this)
        gameThread?.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        loadLevel(currentLevelIndex, width, height)
    }

    private fun loadLevel(levelIndex: Int, screenWidth: Int, screenHeight: Int) {
        if (levelIndex >= LevelManager.levels.size) {
            currentLevelIndex = 0
        }
        currentLevelMap = LevelManager.levels[currentLevelIndex]
        
        val cols = currentLevelMap[0].size
        val rows = currentLevelMap.size
        
        val tileW = screenWidth / cols.toFloat()
        val tileH = screenHeight / rows.toFloat()
        tileSize = Math.min(tileW, tileH)
        
        mapOffsetX = (screenWidth - cols * tileSize) / 2f
        mapOffsetY = (screenHeight - rows * tileSize) / 2f
        
        val startPoint = LevelManager.startPoints[currentLevelIndex]
        carX = mapOffsetX + startPoint[0] * tileSize + tileSize / 2f
        carY = mapOffsetY + startPoint[1] * tileSize + tileSize / 2f
        angle = startPoint[2]
        velocity = 0f
        hasFinishedLevel = false
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        var retry = true
        isRunning = false
        while (retry) {
            try {
                gameThread?.join()
                retry = false
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
        udpSocket?.close()
        soundPool?.release()
    }

    override fun run() {
        while (isRunning) {
            val startTime = System.currentTimeMillis()
            
            updatePhysics()
            updateAudio()
            drawFrame()
            
            val frameTime = System.currentTimeMillis() - startTime
            val targetTime = 1000L / 60L // 60 FPS
            if (frameTime < targetTime) {
                Thread.sleep(targetTime - frameTime)
            }
        }
    }
    
    private fun updateAudio() {
        val speed = Math.abs(velocity)
        if (speed > 0.5f) {
            if (!isEnginePlaying) {
                engineStreamId = soundPool?.play(engineSoundId, 0.3f, 0.3f, 1, -1, 1.0f) ?: 0
                isEnginePlaying = true
            }
            // Increase engine pitch (rate) as speed increases
            val rate = 0.8f + (speed / maxSpeed) * 1.2f
            soundPool?.setRate(engineStreamId, rate)
        } else {
            if (isEnginePlaying) {
                soundPool?.pause(engineStreamId)
                isEnginePlaying = false
            }
        }
    }

    private fun updatePhysics() {
        if (hasFinishedLevel) {
            if (System.currentTimeMillis() - levelFinishTimer > 3000) {
                currentLevelIndex++
                loadLevel(currentLevelIndex, width, height)
            }
            return 
        }
        
        val deadzoneX = 1.0f
        if (inputX > deadzoneX) {
            velocity += acceleration * (inputX / 9.8f)
        } else if (inputX < -deadzoneX) {
            velocity += acceleration * (inputX / 9.8f) * 1.5f 
        }
        
        velocity *= friction 
        
        if (velocity > maxSpeed) velocity = maxSpeed
        if (velocity < -maxSpeed / 2) velocity = -maxSpeed / 2
        
        val deadzoneY = 1.0f
        if (Math.abs(velocity) > 0.5f) {
            if (inputY > deadzoneY || inputY < -deadzoneY) {
                val directionMultiplier = if (velocity > 0) 1f else -1f
                angle += steeringSensitivity * (inputY / 9.8f) * (velocity / maxSpeed) * 15f * directionMultiplier
            }
        }
        
        val radians = Math.toRadians(angle.toDouble())
        val vx = (cos(radians) * velocity).toFloat()
        val vy = (sin(radians) * velocity).toFloat()
        
        val nextX = carX + vx
        val nextY = carY + vy
        
        val tileType = getTileTypeAt(nextX, nextY)
        
        if (tileType == 0) {
            // Collision with grass/wall
            val crashSpeed = Math.abs(velocity)
            velocity *= -0.4f 
            
            // Play crash sound if hitting hard enough
            if (crashSpeed > 5f) {
                soundPool?.play(crashSoundId, 1.0f, 1.0f, 1, 0, 1.0f)
            }
        } else {
            carX = nextX
            carY = nextY
            
            if (tileType == 2 && velocity > 2f) { 
                hasFinishedLevel = true
                levelFinishTimer = System.currentTimeMillis()
                velocity = 0f
            }
        }
    }
    
    private fun getTileTypeAt(pixelX: Float, pixelY: Float): Int {
        val tx = ((pixelX - mapOffsetX) / tileSize).toInt()
        val ty = ((pixelY - mapOffsetY) / tileSize).toInt()
        
        if (ty >= 0 && ty < currentLevelMap.size && tx >= 0 && tx < currentLevelMap[0].size) {
            return currentLevelMap[ty][tx]
        }
        return 0 
    }

    private fun drawFrame() {
        if (holder.surface.isValid) {
            val canvas: Canvas = holder.lockCanvas() ?: return
            
            canvas.drawColor(Color.BLACK)
            
            val cols = currentLevelMap[0].size
            val rows = currentLevelMap.size
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    val type = currentLevelMap[y][x]
                    val rect = RectF(
                        mapOffsetX + x * tileSize,
                        mapOffsetY + y * tileSize,
                        mapOffsetX + (x + 1) * tileSize,
                        mapOffsetY + (y + 1) * tileSize
                    )
                    when (type) {
                        0 -> {
                            if (grassBitmap != null) {
                                canvas.drawBitmap(grassBitmap!!, null, rect, null)
                            } else {
                                canvas.drawRect(rect, Paint().apply { color = Color.parseColor("#228B22") })
                            }
                        }
                        1 -> {
                            if (roadBitmap != null) {
                                canvas.drawBitmap(roadBitmap!!, null, rect, null)
                            } else {
                                canvas.drawRect(rect, Paint().apply { color = Color.parseColor("#444444") })
                            }
                        }
                        2 -> canvas.drawRect(rect, finishLinePaint)
                    }
                }
            }
            
            // Draw Car
            canvas.save()
            canvas.translate(carX, carY)
            canvas.rotate(angle)
            
            if (carBitmap != null) {
                // Dynamically scale the car sprite based on tile size
                val visualWidth = tileSize * 0.9f
                val visualHeight = tileSize * 0.45f
                val carRect = RectF(-visualWidth/2, -visualHeight/2, visualWidth/2, visualHeight/2)
                canvas.drawBitmap(carBitmap!!, null, carRect, null)
            } else {
                val carBody = RectF(-carWidth, -carHeight, carWidth, carHeight)
                canvas.drawRoundRect(carBody, 10f, 10f, Paint().apply { color = Color.RED })
            }
            
            canvas.restore()
            
            canvas.drawText("LEVEL: ${currentLevelIndex + 1}", 50f, 80f, textPaint)
            canvas.drawText("Speed: ${Math.abs(velocity.toInt()) * 5} km/h", 50f, 150f, textPaint)
            
            if (hasFinishedLevel) {
                val winPaint = Paint().apply { 
                    color = Color.YELLOW
                    textSize = 100f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                    setShadowLayer(10f, 5f, 5f, Color.BLACK)
                }
                canvas.drawText("LEVEL CLEARED!", width / 2f, height / 2f, winPaint)
            }
            
            holder.unlockCanvasAndPost(canvas)
        }
    }
}
