package knf.kuma

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.StatFs
import android.text.format.Formatter
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Observer
import com.afollestad.materialdialogs.MaterialDialog
import com.google.android.material.floatingactionbutton.FloatingActionButton
import fr.bmartel.speedtest.SpeedTestReport
import fr.bmartel.speedtest.SpeedTestSocket
import fr.bmartel.speedtest.inter.ISpeedTestListener
import fr.bmartel.speedtest.model.SpeedTestError
import knf.kuma.backup.Backups
import knf.kuma.backup.firestore.FirestoreManager
import knf.kuma.commons.*
import knf.kuma.custom.GenericActivity
import knf.kuma.custom.StateView
import knf.kuma.database.CacheDB
import knf.kuma.directory.DirectoryService
import knf.kuma.directory.DirectoryUpdateService
import knf.kuma.download.FileAccessHelper
import kotlinx.android.synthetic.main.layout_diagnostic.*
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.find
import org.jetbrains.anko.sdk27.coroutines.onClick
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import java.math.BigDecimal
import java.math.RoundingMode

class Diagnostic : GenericActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(EAHelper.getTheme())
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_diagnostic)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Diagnóstico"
        supportActionBar?.setDisplayShowHomeEnabled(false)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        startTests()
    }

    private fun startTests() {
        runMainTest()
        runBypassTest()
        runInternetTest()
        runDirectoryTest()
        runMemoryTest()
        runBackupTest()
    }

    private fun runMainTest() {
        doAsync {
            val startTime = System.currentTimeMillis()
            val responseCode = try {
                val response = Jsoup.connect("https://animeflv.net/").timeout(0).execute()
                response.body()
                response.statusCode()
            } catch (e: HttpStatusException) {
                e.statusCode
            }
            val loadingTime = System.currentTimeMillis() - startTime
            doOnUI {
                codeState.load(responseCode.toString(), when (responseCode) {
                    200 -> StateView.STATE_OK
                    503 -> StateView.STATE_WARNING
                    else -> StateView.STATE_ERROR
                })
                timeoutState.load("$loadingTime ms", when {
                    loadingTime < 10000 -> StateView.STATE_OK
                    loadingTime < 20000 -> StateView.STATE_WARNING
                    else -> StateView.STATE_ERROR
                })
                generalState.load(when {
                    responseCode == 200 && loadingTime < 10000 -> "Correcto"
                    responseCode == 503 -> "Cloudflare activado"
                    responseCode == 403 -> "Bloqueado por proveedor"
                    loadingTime > 10000 -> "Página lenta"
                    else -> "Desconocido"
                }, when {
                    responseCode == 200 && loadingTime < 10000 -> StateView.STATE_OK.also { info.visibility = View.GONE }
                    responseCode == 503 || responseCode == 403 || loadingTime > 10000 -> StateView.STATE_WARNING.also { info.visibility = View.VISIBLE }
                    else -> StateView.STATE_ERROR.also { info.visibility = View.GONE }
                })
                info.setOnClickListener {
                    when {
                        responseCode == 503 -> show503Info()
                        responseCode == 403 -> show403Info()
                        loadingTime > 10000 -> showTimeoutInfo()
                    }
                }
            }
        }
    }

    private fun runBypassTest() {
        doAsync {
            try {
                Jsoup.connect("https://animeflv.net/").timeout(0).execute()
                bypassState.load("No se necesita")
                doOnUI { bypassRecreate.visibility = View.GONE }
            } catch (e: HttpStatusException) {
                doOnUI {
                    bypassRecreate.apply {
                        visibility = View.VISIBLE
                        onClick { startActivityForResult(Intent(this@Diagnostic, FullBypass::class.java), 5546) }
                    }
                }
                try {
                    jsoupCookies("https://animeflv.net/").timeout(0).get()
                    bypassState.load("Valido", StateView.STATE_OK)
                } catch (e: HttpStatusException) {
                    when (e.statusCode) {
                        503 -> bypassState.load("Caducado", StateView.STATE_WARNING)
                        else -> bypassState.load("Error en página: HTTP ${e.statusCode}", StateView.STATE_ERROR)
                    }
                }
                loadBypassInfo()
            } catch (e: Exception) {
                e.printStackTrace()
                bypassState.load("Error en página: ${e.message}", StateView.STATE_ERROR)
            }
        }
    }

    private fun loadBypassInfo() {
        ipState.apply {
            doAsync {
                val document = Jsoup.connect("http://checkip.org/").get()
                load(document.select("div#yourip h1 span").text())
            }
        }
        clearanceState.apply {
            val data = BypassUtil.getClearance(this@Diagnostic)
            if (data.isNotEmpty())
                load(data)
        }
        cfduidState.apply {
            val data = BypassUtil.getCFDuid(this@Diagnostic)
            if (data.isNotEmpty())
                load(data)
        }
        userAgentState.apply {
            load(BypassUtil.userAgent)
        }
    }

    private fun runInternetTest() {
        doAsync {
            SpeedTestSocket().apply {
                addSpeedTestListener(object : ISpeedTestListener {
                    override fun onCompletion(report: SpeedTestReport?) {
                        report?.let { downState.load(formatBigDecimal(it.transferRateOctet)) }
                    }

                    override fun onProgress(percent: Float, report: SpeedTestReport?) {
                        report?.let { downState.load(formatBigDecimal(it.transferRateOctet)) }
                    }

                    override fun onError(speedTestError: SpeedTestError?, errorMessage: String?) {
                        downState.load("Error: ${errorMessage ?: ""}", StateView.STATE_ERROR)
                    }
                })
                startDownload("http://1.testdebit.info/10M.iso")
            }
        }
        doAsync {
            SpeedTestSocket().apply {
                addSpeedTestListener(object : ISpeedTestListener {
                    override fun onCompletion(report: SpeedTestReport?) {
                        report?.let { upState.load(formatBigDecimal(it.transferRateOctet)) }
                    }

                    override fun onProgress(percent: Float, report: SpeedTestReport?) {
                        report?.let { upState.load(formatBigDecimal(it.transferRateOctet)) }
                    }

                    override fun onError(speedTestError: SpeedTestError?, errorMessage: String?) {
                        upState.load("Error: ${errorMessage ?: ""}", StateView.STATE_ERROR)
                    }
                })
                startUpload("http://ipv4.ikoula.testdebit.info/", 5000000)
            }
        }
    }

    private fun formatBigDecimal(bigDecimal: BigDecimal): String {
        var decimal = bigDecimal.movePointLeft(3)
        val unit = when {
            decimal >= BigDecimal.valueOf(1000000) -> {
                decimal = decimal.movePointLeft(6)
                "Gb/s"
            }
            decimal >= BigDecimal.valueOf(1000) -> {
                decimal = decimal.movePointLeft(3)
                "Mb/s"
            }
            else -> "Kb/s"
        }
        return "${decimal.setScale(1, RoundingMode.HALF_UP)}$unit~"
    }

    private fun runDirectoryTest() {
        dirState.load(when {
            PrefsUtil.isDirectoryFinished && !DirectoryUpdateService.isRunning -> "Completo"
            PrefsUtil.isDirectoryFinished && DirectoryUpdateService.isRunning -> "Actualizando"
            !PrefsUtil.isDirectoryFinished && DirectoryService.isRunning -> "Creando"
            else -> "Incompleto"
        })
        CacheDB.INSTANCE.animeDAO().countLive.observe(this, Observer {
            dirTotalState.load(it.toString())
        })
    }

    private fun runMemoryTest() {
        internalState.load(getAvailable(FileAccessHelper.internalRoot.path))
        FileAccessHelper.externalRoot?.path?.let { externalState.load(getAvailable(it)) }
    }

    private fun getAvailable(path: String): String {
        val stat = StatFs(path)
        return Formatter.formatFileSize(this, stat.blockSizeLong * stat.availableBlocksLong)
    }

    private fun runBackupTest() {
        uuid.text = FirestoreManager.uid ?: "Solo firestore"
        backupState.load(when (Backups.type) {
            Backups.Type.DROPBOX -> "Dropbox"
            Backups.Type.FIRESTORE -> "Firestore"
            Backups.Type.LOCAL -> "Local"
            else -> "Sin respaldos"
        })
        if (Backups.type != Backups.Type.NONE)
            lastBackupState.load(PrefsUtil.lastBackup)
    }

    private fun show503Info() {
        MaterialDialog(this).safeShow {
            title(text = "HTTP 503")
            message(text = "Animeflv tiene el cloudflare activado, la app crea un bypass para funcionar normalmente")
        }
    }

    private fun show403Info() {
        MaterialDialog(this).safeShow {
            title(text = "HTTP 403")
            message(text = "Tu proveedor de internet bloquea la conexión con Animeflv, reinicia tu modem!")
        }
    }

    private fun showTimeoutInfo() {
        MaterialDialog(this).safeShow {
            title(text = "Timeout")
            message(text = "La página de Animeflv carga muy lento, modifica la espera de conexión desde configuración")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 5546)
            runBypassTest()
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, Diagnostic::class.java))
        }
    }

    class FullBypass : GenericActivity() {
        private val overlay: View by lazy { find(R.id.overlay) as View }
        private val logText: TextView by lazy { find(R.id.logText) as TextView }
        private val fab: FloatingActionButton by lazy { find(R.id.fab) as FloatingActionButton }
        private var isOpened = false
        private var isFinishPending = false
        private val builder = StringBuilder("Initializing log...\n")
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(EAHelper.getTheme())
            super.onCreate(savedInstanceState)
            try {
                setContentView(R.layout.activity_webview)
            } catch (e: Exception) {
                setContentView(R.layout.activity_webview_nwv)
            }
            logText.movementMethod = ScrollingMovementMethod()
            fab.setOnClickListener {
                if (isOpened && isFinishPending)
                    finish()
                else if (isOpened) {
                    isOpened = false
                    overlay.visibility = View.GONE
                    logText.visibility = View.GONE
                    fab.setImageResource(R.drawable.ic_terminal)
                } else {
                    isOpened = true
                    overlay.visibility = View.VISIBLE
                    logText.visibility = View.VISIBLE
                    fab.setImageResource(R.drawable.ic_close)
                }
            }
            logText("On Create check")
            checkBypass()
        }

        override fun forceCreation(): Boolean = true

        //override fun getSnackbarAnchor(): View? = find(R.id.coordinator)

        override fun onBypassUpdated() {
            if (isOpened)
                isFinishPending = true
            else
                finish()
        }

        override fun logText(text: String) {
            super.logText(text)
            builder.apply {
                append(text)
                append("\n")
            }
            doOnUI { logText.text = builder.toString() }
        }
    }
}