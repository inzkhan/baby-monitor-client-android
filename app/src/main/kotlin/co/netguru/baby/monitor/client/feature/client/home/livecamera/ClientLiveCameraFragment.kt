package co.netguru.baby.monitor.client.feature.client.home.livecamera

import android.Manifest
import android.app.Service
import android.arch.lifecycle.ViewModelProvider
import android.arch.lifecycle.ViewModelProviders
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import co.netguru.baby.monitor.client.R
import co.netguru.baby.monitor.client.feature.client.home.ClientHomeViewModel
import co.netguru.baby.monitor.client.feature.common.extensions.allPermissionsGranted
import co.netguru.baby.monitor.client.feature.common.extensions.and
import co.netguru.baby.monitor.client.feature.common.extensions.bindService
import co.netguru.baby.monitor.client.feature.communication.webrtc.CallState
import co.netguru.baby.monitor.client.feature.communication.webrtc.WebRtcService
import co.netguru.baby.monitor.client.feature.communication.websocket.ClientHandlerService
import dagger.android.support.DaggerFragment
import io.reactivex.disposables.CompositeDisposable
import kotlinx.android.synthetic.main.fragment_client_live_camera.*
import timber.log.Timber
import javax.inject.Inject

class ClientLiveCameraFragment : DaggerFragment(), ServiceConnection {

    @Inject
    internal lateinit var factory: ViewModelProvider.Factory

    private val viewModel by lazy {
        ViewModelProviders.of(requireActivity(), factory)[ClientHomeViewModel::class.java]
    }
    private val compositeDisposable = CompositeDisposable()
    private var childServiceBinder: ClientHandlerService.ChildServiceBinder? = null
    private var webRtcBinder: WebRtcService.MainBinder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.shouldHideNavbar.postValue(true)
    }

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ) = inflater.inflate(R.layout.fragment_client_live_camera, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        clientLiveCameraStopIbtn.setOnClickListener {
            viewModel.callCleanUp {
                requireActivity().onBackPressed()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!requireContext().allPermissionsGranted(permissions)) {
            requestPermissions(permissions, PERMISSIONS_REQUEST_CODE)
        } else {
            bindServices()
        }
    }

    override fun onPause() {
        super.onPause()
        if (webRtcBinder != null) {
            requireContext().unbindService(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webRtcBinder?.cleanup()
        viewModel.shouldHideNavbar.postValue(false)
        childServiceBinder?.refreshChildWebSocketConnection(viewModel.selectedChild.value?.address)
        compositeDisposable.dispose()
    }

    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requireContext().allPermissionsGranted(Companion.permissions)) {
            bindServices()
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        Timber.i("onServiceDisconnected")
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        when (service) {
            is WebRtcService.MainBinder -> {
                Timber.i("WebRtcService service connected")
                webRtcBinder = service
            }
            is ClientHandlerService.ChildServiceBinder -> {
                Timber.i("ClientHandlerService service connected")
                childServiceBinder = service
            }
        }
        with((webRtcBinder and childServiceBinder)) {
            this ?: return@with
            val address = viewModel.selectedChild.value?.address ?: return@with
            val client = first.createClient(
                    second.getChildClient(address) ?: return@with
            )

            viewModel.startCall(
                    client,
                    requireActivity().applicationContext,
                    this@ClientLiveCameraFragment::handleStateChange
            )
            viewModel.setRemoteRenderer(liveCameraRemoteRenderer)
        }
    }

    private fun bindServices() {
        bindService(
                WebRtcService::class.java,
                this,
                Service.BIND_AUTO_CREATE
        )
        bindService(
                ClientHandlerService::class.java,
                this,
                Service.BIND_AUTO_CREATE
        )
    }

    private fun handleStateChange(state: CallState) {
        Timber.i(state.toString())
    }

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 125

        private val permissions = arrayOf(
                Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA
        )
    }
}
