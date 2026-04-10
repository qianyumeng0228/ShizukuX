package af.shizuku.manager.legacy

import android.os.Bundle
import android.widget.Toast
import af.shizuku.manager.app.AppActivity
import af.shizuku.manager.shell.ShellBinderRequestHandler

class ShellRequestHandlerActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ShellBinderRequestHandler.handleRequest(this, intent)
        finish()
    }
}
