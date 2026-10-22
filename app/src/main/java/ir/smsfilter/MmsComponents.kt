package ir.smsfilter

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Required components for holding the SMS role on modern Android. MMS is not implemented yet:
 * the push is accepted (so nothing crashes and the role stays valid) and the user gets a
 * notification pointing at MIUI Messages, which can still read the shared provider.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // TODO real MMS download (WAP push -> MMSC fetch). Until then: say so instead of failing silently.
        android.util.Log.i("SMSFilter", "MMS push received, download not implemented yet")
    }
}

class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
