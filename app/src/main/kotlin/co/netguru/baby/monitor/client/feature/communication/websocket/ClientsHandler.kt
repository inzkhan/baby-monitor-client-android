package co.netguru.baby.monitor.client.feature.communication.websocket

import co.netguru.baby.monitor.client.common.NotificationHandler
import co.netguru.baby.monitor.client.common.RunsInBackground
import co.netguru.baby.monitor.client.data.DataRepository
import co.netguru.baby.monitor.client.data.client.home.log.LogDataEntity
import co.netguru.baby.monitor.client.data.communication.websocket.ConnectionStatus
import co.netguru.baby.monitor.client.feature.communication.webrtc.base.RtcCall
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.addTo
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import org.java_websocket.framing.CloseFrame
import org.json.JSONObject
import org.threeten.bp.LocalDateTime
import timber.log.Timber
import java.util.concurrent.TimeUnit

class ClientsHandler(
        private val babyNameObservable: Observable<String>,
        private val listener: ConnectionListener,
        private val notificationHandler: NotificationHandler,
        private val dataRepository: DataRepository
) {

    private var webSocketClient: CustomWebSocketClient? = null
    private var address = ""
    private val compositeDisposable = CompositeDisposable()
    private var notificationsEnabled = true;

    @RunsInBackground
    fun addClient(address: String) = Completable.fromAction {
        if (webSocketClient == null) {
            connect(address)
            this.address = address
        }
    }

    fun getClient(address: String?) = webSocketClient

    fun reconnectClient(address: String) {
        webSocketClient?.let { client ->
            client.closeClient()
                    .subscribeOn(Schedulers.newThread())
                    .subscribeBy(
                            onComplete = {
                                client.onDestroy()
                                connect(address)
                            },
                            onError = Timber::e
                    ).addTo(compositeDisposable)
        }
    }

    fun notificationsEnabled(enabled: Boolean){
        notificationsEnabled = enabled
    }

    fun onDestroy() {
        compositeDisposable.dispose()
        try {
            webSocketClient?.close(CloseFrame.FLASHPOLICY, "")
            webSocketClient?.onDestroy()

        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun onAvailabilityChange(client: CustomWebSocketClient, status: ConnectionStatus) {
        when (status) {
            ConnectionStatus.UNKNOWN -> Unit
            ConnectionStatus.CONNECTED -> listener.onConnectionStatusChange(client)
            ConnectionStatus.DISCONNECTED -> retryConnection(client.address)
            ConnectionStatus.RETRYING -> Unit
        }
    }

    private fun onMessageReceived(client: CustomWebSocketClient, message: String?) {
        val json = JSONObject(message)
        if (json.has(RtcCall.WEB_SOCKET_ACTION_KEY)) {
            when (json.getString(RtcCall.WEB_SOCKET_ACTION_KEY)) {
                RtcCall.BABY_IS_CRYING -> {
                    if (notificationsEnabled) {
                        notificationHandler.showBabyIsCryingNotification()
                        addLogData(client.address)
                    } else {
                        Timber.i("Notifications disabled")
                    }
                }
                else -> Timber.e("Unrecognized action from message: $message")
            }
        }
    }

    private fun addLogData(address: String) {
        dataRepository.insertLogToDatabase(
                LogDataEntity(
                        BABY_WAS_CRYING_EVENT,
                        LocalDateTime.now().toString(),
                        address
                )
        ).subscribeOn(Schedulers.io())
                .subscribeBy(
                        onComplete = { Timber.i("data inserted") },
                        onError = Timber::e
                )
    }

    private fun retryConnection(address: String) {
        Completable.timer(RETRY_SECONDS_DELAY, TimeUnit.SECONDS)
                .subscribeOn(Schedulers.newThread())
                .subscribeBy(
                        onComplete = {
                            webSocketClient?.let(this::reconnect)
                        }
                ).addTo(compositeDisposable)
        listener.onConnectionStatusChange(webSocketClient ?: return)
    }

    private fun reconnect(client: CustomWebSocketClient) {
        if (client.connectionStatus != ConnectionStatus.CONNECTED) {
            client.asyncReconnect()
                    .subscribeOn(Schedulers.newThread())
                    .subscribeBy(
                            onComplete = { Timber.i("reconnect") },
                            onError = Timber::e
                    ).addTo(compositeDisposable)
        }
    }

    private fun connect(address: String): CustomWebSocketClient? {
        Timber.i("connecting $address...")
        val status = webSocketClient?.connectionStatus ?: ConnectionStatus.UNKNOWN
        val retrying = webSocketClient?.wasRetrying ?: false

        webSocketClient = CustomWebSocketClient(
                address,
                babyNameObservable = babyNameObservable,
                onAvailabilityChange = this::onAvailabilityChange,
                onMessageReceived = this::onMessageReceived
        ).apply {
            connectionStatus = status
            wasRetrying = retrying
        }.also { client ->
            client.connectClient()
                    .subscribeOn(Schedulers.newThread())
                    .subscribeBy(
                            onComplete = {
                                Timber.i("Complete")
                            },
                            onError = {
                                Timber.e("connection error: $it")
                                client.notifyAvailabilityChange(ConnectionStatus.DISCONNECTED)
                            }
                    ).addTo(compositeDisposable)
        }
        return webSocketClient
    }

    interface ConnectionListener {
        fun onConnectionStatusChange(client: CustomWebSocketClient)
    }

    companion object {
        private const val RETRY_SECONDS_DELAY = 10L
        private const val BABY_WAS_CRYING_EVENT = "Baby was crying"
    }
}
