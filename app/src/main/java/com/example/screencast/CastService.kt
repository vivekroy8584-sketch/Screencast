package com.example.screencast

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.IBinder
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import org.webrtc.*

class CastService : Service() {
    private val egl = EglBase.create()
    private lateinit var factory: PeerConnectionFactory
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var track: VideoTrack? = null
    private var server: Server? = null
    private var pc: PeerConnection? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cast", "Casting", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "cast").setContentTitle("Casting screen")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val data = intent?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()

        capturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        })
        helper = SurfaceTextureHelper.create("cap", egl.eglBaseContext)
        val src = factory.createVideoSource(true)
        capturer!!.initialize(helper, this, src.capturerObserver)
        val dm = resources.displayMetrics
        val h = 720; val w = (h * dm.widthPixels.toFloat() / dm.heightPixels).toInt() / 2 * 2
        capturer!!.startCapture(w, h, 30)
        track = factory.createVideoTrack("screen", src)

        server = Server().also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop(); pc?.close()
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose(); helper?.dispose(); egl.release()
        super.onDestroy()
    }

    inner class Server : NanoWSD(8080) {
        override fun serveHttp(session: IHTTPSession): Response {
            val html = assets.open("receiver.html").bufferedReader().readText()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
        override fun openWebSocket(h: IHTTPSession): WebSocket = Viewer(h)
    }

    inner class Viewer(h: NanoHTTPD.IHTTPSession) : NanoWSD.WebSocket(h) {
        private fun out(o: JSONObject) { try { send(o.toString()) } catch (_: Exception) {} }

        override fun onOpen() {
            pc?.close()
            val conn = factory.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }, object : PeerConnection.Observer {
                override fun onIceCandidate(c: IceCandidate) = out(JSONObject()
                    .put("type", "candidate").put("candidate", c.sdp)
                    .put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex))
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                override fun onAddStream(s: MediaStream?) {}
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onDataChannel(d: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
            })!!
            pc = conn
            conn.addTrack(track, listOf("stream"))
            conn.createOffer(object : SdpAdapter() {
                override fun onCreateSuccess(d: SessionDescription) {
                    conn.setLocalDescription(SdpAdapter(), d)
                    out(JSONObject().put("type", "offer").put("sdp", d.description))
                }
            }, MediaConstraints())
        }

        override fun onMessage(m: NanoWSD.WebSocketFrame) {
            val j = JSONObject(m.textPayload)
            when (j.getString("type")) {
                "answer" -> pc?.setRemoteDescription(SdpAdapter(),
                    SessionDescription(SessionDescription.Type.ANSWER, j.getString("sdp")))
                "candidate" -> pc?.addIceCandidate(IceCandidate(
                    j.getString("sdpMid"), j.getInt("sdpMLineIndex"), j.getString("candidate")))
            }
        }
        override fun onClose(c: NanoWSD.WebSocketFrame.CloseCode?, r: String?, i: Boolean) {}
        override fun onPong(p: NanoWSD.WebSocketFrame?) {}
        override fun onException(e: java.io.IOException?) {}
    }

    open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }
}
