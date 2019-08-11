package xyz.helioz.heliolaser

import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.info
import org.jetbrains.anko.wifiManager
import java.net.*
import java.util.concurrent.atomic.AtomicLong


class Audiomixclient : AnkoLogger {
    companion object {
        val audiomixclient : Audiomixclient by lazy {
            Audiomixclient()
        }
    }
    private val serverSendingThreadHandler by lazy {
        val helperThread = HandlerThread(javaClass.canonicalName)
        helperThread.start()
        Handler(helperThread.looper)
    }

    private val DEFAULT_HTTP_ADDRESS = "192.168.1.236:13131"
    private val LISTEN_UDP_PORT = 13232
    private val SEND_UDP_PORT = 13231
    private val EMULATOR_SERVER_IP = "10.0.2.2"
    val datagramSocket: DatagramSocket by lazy {
        val socket = DatagramSocket(LISTEN_UDP_PORT)
        socket.broadcast = true
        socket
    }

    val clientTokenNumber = AtomicLong(System.currentTimeMillis())

    private fun sendUdpAudioMixServerMessage(uri: Uri) {
        val msg = buildRequest(uri, nextToken())
        info { "Audiomixclient starting sending $uri to $broadcastAddress"}
        serverSendingThreadHandler.post {
            tryOrContinue {
                info { "Audiomixclient sending $uri to $broadcastAddress (${msg.size} bytes)"}
                datagramSocket.send(DatagramPacket(msg, msg.size, broadcastAddress, SEND_UDP_PORT))
            }
        }
    }

    private fun sendAllAudioMixServerMessage(uri: Uri) {
        sendUdpAudioMixServerMessage(uri)
        val httpUri = uri.buildUpon().authority(HelioPref("audiomixserver_http_address", DEFAULT_HTTP_ADDRESS)).build()
        serverSendingThreadHandler.post {
            tryOrContinue {
                info { "audiomixclient requesting $httpUri"}
                val results = URL(httpUri.toString()).content
                info { "audiomixclient received $results for $httpUri"}
            }
        }
    }

    fun play_morse_message(message: String) {
        sendAllAudioMixServerMessage(
                Uri.Builder().path("play_morse_message").appendQueryParameter("message", message).build()
        )
    }

    private fun buildRequest(command: Uri, token: String): ByteArray {
        val builder = StringBuilder()
        builder.append("audiomixclient/3 helioandroidmorse 1\n")
                .append(token).append("\n")
                .append(command).append("\n")
        return builder.toString().toByteArray()
    }

    private val broadcastAddress by lazy {
        val wifi = HelioLaserApplication.helioLaserApplicationInstance?.applicationContext?.wifiManager
        val dhcp = wifi?.dhcpInfo
        if (dhcp == null || dhcp.ipAddress == 0) {
            // assume we are on the emulator
            InetAddress.getByName(EMULATOR_SERVER_IP)
        } else {
            val broadcast = dhcp.ipAddress and dhcp.netmask or dhcp.netmask.inv()
            InetAddress.getByAddress(
                    byteArrayOf(broadcast.ushr(24).toByte(),
                            broadcast.ushr(16).toByte(),
                            broadcast.ushr(8).toByte(),
                            broadcast.toByte()))
        }
    }

    private fun nextToken(): String {
        return clientTokenNumber.incrementAndGet().toString()
    }

    init {
        sendUdpAudioMixServerMessage(Uri.Builder().path("reset").build())
    }
}