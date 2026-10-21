package link.yggdrasil.yggstack.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat

/**
 * No-display trampoline for incoming yggstack backup TOMLs, covering both
 * ACTION_SEND (share sheet) and ACTION_VIEW ("open with" from a file
 * manager); see the manifest filters. Deliberately separate from
 * MainActivity so delivery never depends on MainActivity's task state: this
 * activity reads the content on its own thread, publishes it to
 * [MainActivity.pendingSharedBackupFlow] (thread-safe) and brings the app
 * forward. MainScreen's collector then shows the import preview whether
 * MainActivity was cold-started, stopped in the background or already on
 * screen — warm and cold deliveries behave identically.
 *
 * The manifest matches generic mime types (including the wildcard some file
 * managers use for unknown extensions like .toml), so anything that is not
 * a backup is rejected later by the backup parser.
 */
class ShareReceiveActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val receivedIntent = intent
        // Bring the app forward first; the preview appears as soon as the
        // read lands in the flow, whatever MainActivity's state is
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        // A plain thread, not a lifecycle scope: the read must survive this
        // activity's immediate finish()
        Thread {
            val raw = runCatching { readBackupContent(receivedIntent) }.getOrNull()
            if (raw != null) {
                MainActivity.pendingSharedBackupFlow.value = raw
            } else {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        applicationContext,
                        R.string.backup_receive_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
        // Theme.NoDisplay activities must finish immediately
        finish()
    }

    /** The shared file/text (ACTION_SEND) or the opened file (ACTION_VIEW). */
    private fun readBackupContent(intent: Intent): String? {
        when (intent.action) {
            Intent.ACTION_VIEW -> return intent.data?.let(::readUri)
            Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) return readUri(uri)
                return intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    /**
     * Reads a backup content URI capped at 1 MB — a real backup is a few KB,
     * so anything larger is not one and is not worth pulling into memory.
     * Runs on the caller's worker thread.
     */
    private fun readUri(uri: Uri): String? {
        return contentResolver.openInputStream(uri)?.use { input ->
            val cap = 1 shl 20
            val bytes = ByteArray(cap)
            var off = 0
            while (off < cap) {
                val read = input.read(bytes, off, cap - off)
                if (read < 0) break
                off += read
            }
            if (off >= cap) null else String(bytes, 0, off)
        }
    }
}
