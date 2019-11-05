package co.netguru.baby.monitor.client.application

import co.netguru.baby.monitor.client.application.firebase.FirebaseRepository
import com.jakewharton.threetenabp.AndroidThreeTen
import dagger.android.AndroidInjector
import dagger.android.support.DaggerApplication
import io.reactivex.plugins.RxJavaPlugins
import javax.inject.Inject

class App : DaggerApplication() {

    @Inject
    lateinit var debugMetricsHelper: DebugMetricsHelper

    @Inject
    lateinit var rxJavaErrorHandler: RxJavaErrorHandler

    @Inject
    lateinit var firebaseRepository: FirebaseRepository

    @Inject
    lateinit var buildTypeModule: BuildTypeModule

    override fun onCreate() {
        super.onCreate()
        debugMetricsHelper.init()
        RxJavaPlugins.setErrorHandler(rxJavaErrorHandler)
        AndroidThreeTen.init(this)
        firebaseRepository.initializeApp(this)
        buildTypeModule.initialize(this)
    }

    override fun applicationInjector(): AndroidInjector<App> =
            DaggerApplicationComponent.builder().create(this)

    override fun onTerminate() {
        firebaseRepository.clear()
        super.onTerminate()
    }
}
