package co.netguru.baby.monitor.client.feature.communication.webrtc.receiver

import android.app.Service
import android.content.Intent
import co.netguru.baby.monitor.client.data.DataRepository
import co.netguru.baby.monitor.client.common.view.CustomSurfaceViewRenderer
import co.netguru.baby.monitor.client.data.communication.webrtc.CallState
import co.netguru.baby.monitor.client.data.communication.websocket.MessageConfirmationStatus
import co.netguru.baby.monitor.client.feature.communication.webrtc.base.RtcCall
import co.netguru.baby.monitor.client.feature.communication.webrtc.base.RtcCall.Companion.EVENT_RECEIVED_CONFIRMATION
import co.netguru.baby.monitor.client.feature.communication.webrtc.base.RtcCall.Companion.P2P_OFFER
import co.netguru.baby.monitor.client.feature.communication.webrtc.base.RtcCall.Companion.PUSH_NOTIFICATIONS_KEY
import co.netguru.baby.monitor.client.feature.communication.webrtc.base.RtcCall.Companion.WEB_SOCKET_ACTION_KEY
import co.netguru.baby.monitor.client.feature.communication.webrtc.base.RtcCall.Companion.WEB_SOCKET_ACTION_RINGING
import co.netguru.baby.monitor.client.feature.communication.webrtc.base.WebRtcBinder
import co.netguru.baby.monitor.client.data.communication.ClientEntity
import co.netguru.baby.monitor.client.feature.communication.websocket.CustomWebSocketServer
import dagger.android.AndroidInjection
import io.reactivex.Maybe
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import org.java_websocket.WebSocket
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class WebRtcReceiverService : Service() {

    @Inject
    internal lateinit var dataRepository: DataRepository

    private val compositeDisposable = CompositeDisposable()
    private var server: CustomWebSocketServer? = null
    private lateinit var binder: WebRtcReceiverBinder

    private var messageConfirmationMap = mutableMapOf<String, MessageConfirmationStatus>()
    private var firebaseKeysMap = mutableMapOf<String, String>()
    private val messageConfirmationDisposable = CompositeDisposable()

    override fun onCreate() {
        AndroidInjection.inject(this)
        super.onCreate()
        initWebSocketServer()
        getClientsData()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?) = WebRtcReceiverBinder().also { binder = it }

    override fun onDestroy() {
        binder.cleanup()
        server?.let(::stopServer)
        compositeDisposable.dispose()
        messageConfirmationDisposable.dispose()
        super.onDestroy()
    }

    private fun initWebSocketServer() {
        server = CustomWebSocketServer(SERVER_PORT,
                onMessageReceived = this::handleMessage,
                onErrorListener = Timber::e
        ).apply {
            startServer().subscribeOn(Schedulers.io()).subscribeBy(
                    onComplete = {
                        Timber.e("CustomWebSocketServer started")
                    },
                    onError = {
                        Timber.e("launch failed $it")
                        stopServer()
                    }
            ).addTo(compositeDisposable)
        }
    }

    private fun getClientsData() {
        dataRepository.getAllClientData()
                .subscribeOn(Schedulers.newThread())
                .subscribeBy(
                        onNext = { list ->
                            Timber.i("loaded ${list.size} clients")
                            for (client in list) {
                                if (messageConfirmationMap[client.address] == null) {
                                    messageConfirmationMap[client.address] = MessageConfirmationStatus.UNDEFINED
                                    firebaseKeysMap[client.address] = client.firebaseKey
                                }
                            }
                        },
                        onError = Timber::e
                ).addTo(compositeDisposable)
    }

    private fun handleMessage(client: WebSocket?, message: String?) {
        if (client == null || message == null) return
        val jsonObject = JSONObject(message)


        if (jsonObject.has(P2P_OFFER)) {
            handleP2POffer(client, jsonObject)
        }
        if (jsonObject.has(WEB_SOCKET_ACTION_KEY)) {
            handleWebSocketAction(client, jsonObject)
        }
    }

    private fun handleWebSocketAction(client: WebSocket, jsonObject: JSONObject) {
        when (jsonObject.getString(WEB_SOCKET_ACTION_KEY)) {
            EVENT_RECEIVED_CONFIRMATION -> {
                messageConfirmationMap[client.localSocketAddress.address.toString()] = MessageConfirmationStatus.CONFIRMED
            }
            PUSH_NOTIFICATIONS_KEY -> {
                if (jsonObject.has("value")) {
                    handlePushNotificationKey(
                            client.remoteSocketAddress.address.hostAddress,
                            jsonObject.getString("value")
                    )
                }
            }
        }
    }

    private fun handleP2POffer(client: WebSocket, jsonObject: JSONObject) {
        Timber.i("$WEB_SOCKET_ACTION_RINGING...")
        binder.currentCall?.let { call ->
            call.accept(
                    client,
                    jsonObject.getJSONObject(P2P_OFFER).getString("sdp")
            ).subscribeOn(Schedulers.newThread())
                    .subscribeBy(
                            onComplete = { Timber.i("completed") },
                            onError = Timber::e
                    ).addTo(compositeDisposable)
        }
    }

    private fun handlePushNotificationKey(address: String, notificationKey: String) {
        dataRepository.insertClientData(
                ClientEntity(address, notificationKey)
        ).subscribeOn(Schedulers.io())
                .subscribeBy(
                        onComplete = { Timber.i("new firebase key added for address: $address") },
                        onError = Timber::e
                ).addTo(compositeDisposable)
    }

    private fun stopServer(server: CustomWebSocketServer) {
        server.stopServer()
                .subscribeOn(Schedulers.io())
                .subscribeBy(
                        onComplete = {
                            Timber.e("CustomWebSocketServer closed")
                        },
                        onError = {
                            Timber.e("stop failed $it")
                        }
                ).addTo(compositeDisposable)
    }

    inner class WebRtcReceiverBinder : WebRtcBinder() {

        var currentCall: RtcReceiver? = null

        fun createReceiver(
                view: CustomSurfaceViewRenderer,
                listener: (state: CallState) -> Unit
        ) {
            currentCall = RtcReceiver(applicationContext, view, listener)
        }

        fun hangUpReceiver() = Maybe.just(currentCall)
                .flatMapCompletable { call ->
                    server?.let(this@WebRtcReceiverService::stopServer)
                    initWebSocketServer()
                    call.stopCall()
                }

        override fun cleanup() {
            Timber.i("cleanup")
            currentCall?.localView = null
            currentCall?.let(this::callCleanup)
            server?.stop()
        }

        fun handleBabyCrying() {
            server?.broadcast(generateCryingEventJSON().toByteArray())
            waitForResponse()
        }

        fun startCapturer() {
            currentCall?.startCapturer()
        }

        private fun waitForResponse() {
            Single.timer(5, TimeUnit.SECONDS)
                    .map(this::getListOfUnconfirmedAddresses)
                    .subscribeOn(Schedulers.io())
                    .subscribeBy(
                            onSuccess = this::sendNotificationsToAddresses,
                            onError = Timber::e
                    ).addTo(messageConfirmationDisposable)
        }

        private fun getListOfUnconfirmedAddresses(value: Long) = mutableListOf<String>().apply {
            for (data in messageConfirmationMap) {
                if (data.value != MessageConfirmationStatus.CONFIRMED) {
                    firebaseKeysMap[data.key]?.let { key -> add(key) }
                }
            }
        }


        private fun sendNotificationsToAddresses(list: List<String>) {
            for (address in list) {
                //todo 11.01.2019 send firebase notification
            }
        }

        private fun callCleanup(call: RtcCall) {
            call.cleanup(clearSocket = false)
                    .subscribeOn(Schedulers.io())
                    .subscribeBy(
                            onComplete = {
                                server?.let(this@WebRtcReceiverService::stopServer)
                            },
                            onError = Timber::e
                    ).addTo(compositeDisposable)
        }
    }

    companion object {
        const val SERVER_PORT = 10001

        private fun generateCryingEventJSON() = JSONObject().apply {
            put(RtcCall.WEB_SOCKET_ACTION_KEY, RtcCall.BABY_IS_CRYING)
            put("value", "") // iOS is expecting this empty field
        }.toString()
    }
}
