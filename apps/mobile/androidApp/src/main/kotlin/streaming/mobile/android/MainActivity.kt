package streaming.mobile.android

import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import streaming.mobile.*

class MainActivity : ComponentActivity() {
    private lateinit var api: Api
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.rgb(13, 14, 18)))
        api = createApi(BuildConfig.API_ORIGIN, AndroidSecureStore(applicationContext)) { stage, failure ->
            // Never log messages, URLs, frames, credentials or exception bodies.
            if (BuildConfig.DEBUG) android.util.Log.w("VaultTransport", "$stage: ${failure.javaClass.simpleName}")
        }
        intent.dataString?.let(DestinationLinks::open)
        setContent { VaultApp(api) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let(DestinationLinks::open)
    }
    override fun onDestroy() { super.onDestroy(); api.close() }
}
