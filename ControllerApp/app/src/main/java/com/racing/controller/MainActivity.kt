package com.racing.controller

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var gravitySensor: Sensor? = null
    
    private var tvIpAddress = "255.255.255.255" // Broadcast by default
    private val tvPort = 9999
    
    private var udpSocket: DatagramSocket? = null
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        statusText = TextView(this).apply {
            text = "Controller Ready\nWaiting for sensor data..."
            textSize = 24f
            setPadding(32, 32, 32, 32)
        }
        setContentView(statusText)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        thread {
            try {
                udpSocket = DatagramSocket()
                udpSocket?.broadcast = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        gravitySensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        
        val message = "DATA:%.2f,%.2f,%.2f".format(x, y, z)
        
        statusText.text = "Sending:\n$message"

        sendUdpMessage(message)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun sendUdpMessage(message: String) {
        thread {
            try {
                val socket = udpSocket ?: return@thread
                val data = message.toByteArray()
                val address = InetAddress.getByName(tvIpAddress)
                val packet = DatagramPacket(data, data.size, address, tvPort)
                socket.send(packet)
            } catch (e: Exception) {
                // Ignore for now
            }
        }
    }
}
