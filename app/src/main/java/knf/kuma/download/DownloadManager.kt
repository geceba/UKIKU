package knf.kuma.download

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import android.util.Pair
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.crashlytics.android.Crashlytics
import com.tonyodev.fetch2.*
import com.tonyodev.fetch2core.DownloadBlock
import com.tonyodev.fetch2core.Func
import com.tonyodev.fetch2okhttp.OkHttpDownloader
import knf.kuma.App
import knf.kuma.R
import knf.kuma.commons.FileUtil
import knf.kuma.commons.PrefsUtil
import knf.kuma.commons.noCrash
import knf.kuma.database.CacheDB
import knf.kuma.pojos.DownloadObject
import knf.kuma.videoservers.ServersFactory
import okhttp3.OkHttpClient
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.notificationManager
import xdroid.toaster.Toaster
import java.util.*

class DownloadManager : Service() {

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action != null && intent.action == "stop.foreground") {
            stopForeground(true)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(23498, foregroundGroupNotification())
        //notificationManager?.notify(22498, foregroundGroupNotification())
    }

    companion object {
        const val CHANNEL_FOREGROUND = "service.LifeSaver"
        internal const val ACTION_PAUSE = 0
        internal const val ACTION_RESUME = 1
        internal const val ACTION_CANCEL = 2
        private const val CHANNEL = "service.Downloads"
        private const val CHANNEL_ONGOING = "service.Downloads.Ongoing"
        @SuppressLint("StaticFieldLeak")
        private val context: Context = App.context
        private var fetch: Fetch? = null
        private val downloadDao = CacheDB.INSTANCE.downloadsDAO()
        private val notificationManager: NotificationManager by lazy { context.notificationManager }

        fun setParallelDownloads(newValue: String?) {
            if (newValue.isNullOrEmpty()) return
            fetch?.setDownloadConcurrentLimit(Integer.parseInt(newValue))
        }

        init {
            val configuration = FetchConfiguration.Builder(context)
                    .setDownloadConcurrentLimit(PrefsUtil.maxParallelDownloads)
                    .enableLogging(BuildConfig.DEBUG)
                    .enableRetryOnNetworkGain(true)
                    .setAutoRetryMaxAttempts(3)
                    .createDownloadFileOnEnqueue(false)
                    .setHttpDownloader(OkHttpDownloader(OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()))
                    .build()
            fetch = Fetch.getInstance(configuration).addListener(object : FetchListener {
                override fun onAdded(download: Download) {
                    val downloadObject = downloadDao.getByDid(download.id)
                    if (downloadObject != null) {
                        downloadObject.state = DownloadObject.PENDING
                        downloadDao.update(downloadObject)
                    }
                }

                override fun onQueued(download: Download, waitingOnNetwork: Boolean) {
                    val downloadObject = downloadDao.getByDid(download.id)
                    if (downloadObject != null) {
                        downloadObject.state = DownloadObject.PENDING
                        downloadDao.update(downloadObject)
                    }
                }

                override fun onWaitingNetwork(download: Download) {

                }

                override fun onCompleted(download: Download) {
                    val downloadObject = downloadDao.getByDid(download.id)
                    if (downloadObject != null) {
                        if (FileAccessHelper.isTempFile(download.file)) {
                            Log.e("Download", "Moving temp")
                            downloadObject.setEta(-2)
                            downloadObject.progress = 0
                            downloadDao.update(downloadObject)
                            FileUtil.moveFile(downloadObject.file, object : FileUtil.MoveCallback {
                                override fun onProgress(pair: Pair<Int, Boolean>) {
                                    if (!pair.second) {
                                        downloadObject.progress = pair.first
                                        updateNotification(downloadObject, false)
                                        downloadDao.update(downloadObject)
                                    } else if (pair.first == -1) {
                                        downloadDao.delete(downloadObject)
                                        errorNotification(downloadObject)
                                    } else {
                                        downloadObject.progress = 100
                                        downloadObject.state = DownloadObject.COMPLETED
                                        downloadDao.update(downloadObject)
                                        notificationManager.cancel(downloadObject.eid.toInt())
                                        completedNotification(downloadObject)
                                    }
                                    stopIfNeeded()
                                }
                            })
                        } else {
                            downloadObject.state = DownloadObject.COMPLETED
                            downloadDao.update(downloadObject)
                            completedNotification(downloadObject)
                        }
                    }
                    stopIfNeeded()
                }

                override fun onError(download: Download, error: Error, throwable: Throwable?) {
                    Log.e("Download", "Error downloader")
                    throwable?.printStackTrace()
                    Crashlytics.logException(throwable)
                    val downloadObject = downloadDao.getByDid(download.id)
                    if (downloadObject != null) {
                        errorNotification(downloadObject)
                        downloadDao.delete(downloadObject)
                        fetch?.delete(download.id)
                        stopIfNeeded()
                    }
                }

                override fun onDownloadBlockUpdated(download: Download, downloadBlock: DownloadBlock, totalBlocks: Int) {

                }

                override fun onStarted(download: Download, downloadBlocks: List<DownloadBlock>, totalBlocks: Int) {
                    val downloadObject = downloadDao.getByDid(download.id)
                    if (downloadObject != null) {
                        downloadObject.state = DownloadObject.DOWNLOADING
                        downloadDao.update(downloadObject)
                        updateNotification(downloadObject, false)
                    }
                    ContextCompat.startForegroundService(context, Intent(context, DownloadManager::class.java))
                }

                override fun onProgress(download: Download, etaInMilliSeconds: Long, downloadedBytesPerSecond: Long) {
                    val downloadObject = downloadDao.getByDid(download.id)
                    if (downloadObject != null) {
                        downloadObject.state = DownloadObject.DOWNLOADING
                        downloadObject.setEta(etaInMilliSeconds)
                        downloadObject.setSpeed(downloadedBytesPerSecond)
                        downloadObject.progress = download.progress
                        downloadObject.t_bytes = download.total
                        downloadObject.d_bytes = download.downloaded
                        downloadDao.update(downloadObject)
                        updateNotification(downloadObject, false)
                    }
                }

                override fun onPaused(download: Download) {
                    val downloadObject = downloadDao.getByDid(download.id)
                    if (downloadObject != null) {
                        downloadObject.state = DownloadObject.PAUSED
                        downloadObject.setEta(-1)
                        downloadDao.update(downloadObject)
                        updateNotification(downloadObject, true)
                    }
                    stopIfNeeded()
                }

                override fun onResumed(download: Download) {
                    val downloadObject = downloadDao.getByDid(download.id)
                    if (downloadObject != null) {
                        downloadObject.state = DownloadObject.PENDING
                        downloadObject.time = System.currentTimeMillis()
                        downloadDao.update(downloadObject)
                        updateNotification(downloadObject, false)
                    }
                }

                override fun onCancelled(download: Download) {
                    val downloadObject = downloadDao.getByDid(download.id)
                    if (downloadObject != null)
                        notificationManager.cancel(downloadObject.getDid())
                    stopIfNeeded()
                }

                override fun onRemoved(download: Download) {
                    val downloadObject = downloadDao.getByDid(download.id)
                    if (downloadObject != null)
                        notificationManager.cancel(downloadObject.getDid())
                    stopIfNeeded()
                }

                override fun onDeleted(download: Download) {
                    val downloadObject = downloadDao.getByDid(download.id)
                    if (downloadObject != null)
                        notificationManager.cancel(downloadObject.getDid())
                    stopIfNeeded()
                }
            })
        }

        fun start(downloadObject: DownloadObject): Boolean {
            try {
                val file = FileAccessHelper.getFileCreate(downloadObject.file)
                file?.let {
                    val request = Request(downloadObject.link, file.absolutePath)
                    if (downloadObject.headers != null)
                        for (header in downloadObject.headers?.headers
                                ?: listOf<Pair<String, String>>())
                            request.addHeader(header.first, header.second)
                    request.enqueueAction = EnqueueAction.REPLACE_EXISTING
                    downloadObject.setDid(request.id)
                    downloadObject.canResume = true
                    downloadDao.insert(downloadObject)
                    fetch?.enqueue(request, Func { Log.e("Download", "Queued " + it.id) }, Func {
                        it.throwable?.printStackTrace()
                        downloadDao.delete(downloadObject)
                    })
                } ?: return false
                return true
            } catch (e: Exception) {
                e.printStackTrace()
                Toaster.toast("Error al iniciar descarga")
                return false
            }

        }

        fun cancel(eid: String) {
            val downloadObject = downloadDao.getByEid(eid)
            if (downloadObject != null) {
                downloadDao.delete(downloadObject)
                notificationManager.cancel(downloadObject.eid?.toInt() ?: 0)
                if (downloadObject.did != null)
                    fetch?.delete(downloadObject.getDid())
            }
        }

        fun cancelAll() {
            noCrash {
                val downloads = downloadDao.allRaw
                val dids = mutableListOf<Int>()
                downloads.forEach {
                    dids.add(it.getDid())
                    notificationManager.cancel(it.eid?.toInt() ?: 0)
                }
                fetch?.delete(dids)
                downloadDao.delete(downloads)
                stopIfNeeded()
            }
        }

        fun pause(downloadObject: DownloadObject) {
            pause(downloadObject.getDid())
        }

        fun pauseAll() {
            fetch?.getDownloadsWithStatus(Status.DOWNLOADING, Func {
                val list = mutableListOf<Int>()
                it.forEach { download ->
                    list.add(download.id)
                }
                fetch?.pause(list)
            })
        }

        fun pause(did: Int) {
            doAsync { fetch?.pause(did) }
        }

        fun resume(downloadObject: DownloadObject) {
            resume(downloadObject.getDid())
        }

        internal fun resume(did: Int) {
            doAsync { fetch?.resume(did) }
        }

        private fun updateNotification(downloadObject: DownloadObject?, isPaused: Boolean) {
            if (downloadObject == null) return
            val notification = NotificationCompat.Builder(context, CHANNEL_ONGOING).apply {
                setSmallIcon(if (isPaused) R.drawable.ic_pause_not else if (downloadObject.eta.toLong() == -2L) R.drawable.ic_move else android.R.drawable.stat_sys_download)
                setContentTitle(downloadObject.name)
                setContentText(downloadObject.chapter)
                setOnlyAlertOnce(!isPaused || downloadObject.eta.toLong() == -2L)
                setProgress(100, downloadObject.progress, downloadObject.state == DownloadObject.PENDING)
                setGroup("manager")
                setOngoing(!isPaused)
                setSound(null)
                setWhen(downloadObject.time)
                priority = NotificationCompat.PRIORITY_LOW
                if (downloadObject.eta.toLong() != -2L) {
                    if (isPaused)
                        addAction(R.drawable.ic_play_not, "Reanudar", getPending(downloadObject, ACTION_RESUME))
                    else
                        addAction(R.drawable.ic_pause_not, "Pausar", getPending(downloadObject, ACTION_PAUSE))
                    addAction(R.drawable.ic_delete, "Cancelar", getPending(downloadObject, ACTION_CANCEL))
                }
                if (!isPaused)
                    setSubText(downloadObject.subtext)
            }
            notificationManager.notify(downloadObject.eid?.toInt() ?: 0, notification.build())
        }

        private fun completedNotification(downloadObject: DownloadObject) {
            val notification = NotificationCompat.Builder(context, CHANNEL)
                    .setColor(ContextCompat.getColor(context, android.R.color.holo_green_dark))
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(downloadObject.name)
                    .setContentText(downloadObject.chapter)
                    .setContentIntent(ServersFactory.getPlayIntent(context, downloadObject.name, downloadObject.file))
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setWhen(downloadObject.time)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
            notificationManager.notify(downloadObject.eid?.toInt() ?: 0, notification)
            updateMedia(downloadObject)
        }

        private fun errorNotification(downloadObject: DownloadObject) {
            val notification = NotificationCompat.Builder(context, CHANNEL)
                    .setColor(ContextCompat.getColor(context, android.R.color.holo_red_dark))
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(downloadObject.name)
                    .setContentText("Error al descargar " + downloadObject.chapter?.toLowerCase(Locale.ENGLISH))
                    .setOngoing(false)
                    .setWhen(downloadObject.time)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
            notificationManager.notify(downloadObject.eid?.toInt() ?: 0, notification)
        }

        private fun foregroundGroupNotification(): Notification {
            return NotificationCompat.Builder(context, CHANNEL_FOREGROUND).apply {
                setSmallIcon(R.drawable.ic_service)
                setOngoing(true)
                priority = NotificationCompat.PRIORITY_MIN
                if (PrefsUtil.isGroupingEnabled) {
                    setGroup("manager")
                    setGroupSummary(true)
                }
                if (PrefsUtil.collapseDirectoryNotification)
                    setSubText("Descargas en progreso")
                else
                    setContentTitle("Descargas en progreso")
            }.build()
        }

        private fun updateMedia(downloadObject: DownloadObject) {
            try {
                val file = downloadObject.file
                context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(FileAccessHelper.getFile(file))))
                MediaScannerConnection.scanFile(context, arrayOf(FileAccessHelper.getFile(file).absolutePath), arrayOf("video/mp4"), null)
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }

        private fun getPending(downloadObject: DownloadObject, action: Int): PendingIntent {
            return try {
                val intent = Intent(context, DownloadReceiver::class.java)
                        .putExtra("did", downloadObject.getDid())
                        .putExtra("eid", downloadObject.eid)
                        .putExtra("action", action)
                PendingIntent.getBroadcast(context, downloadObject.key + action, intent, PendingIntent.FLAG_CANCEL_CURRENT)
            } catch (e: IllegalStateException) {
                PendingIntent.getBroadcast(context, 0, Intent(), PendingIntent.FLAG_CANCEL_CURRENT)
            }
        }

        private fun stopIfNeeded() {
            if (downloadDao.countActive() == 0) {
                ContextCompat.startForegroundService(context, Intent(context, DownloadManager::class.java).setAction("stop.foreground"))
                notificationManager.cancel(22498)
            }
        }
    }
}
