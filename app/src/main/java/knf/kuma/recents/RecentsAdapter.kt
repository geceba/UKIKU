package knf.kuma.recents

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.UiThread
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.MaterialDialog
import com.google.android.material.card.MaterialCardView
import knf.kuma.BuildConfig
import knf.kuma.R
import knf.kuma.ads.AdCallback
import knf.kuma.ads.AdCardItemHolder
import knf.kuma.ads.AdRecentObject
import knf.kuma.ads.implAdsRecent
import knf.kuma.animeinfo.ActivityAnime
import knf.kuma.backup.firestore.syncData
import knf.kuma.cast.CastMedia
import knf.kuma.commons.*
import knf.kuma.custom.SeenAnimeOverlay
import knf.kuma.database.CacheDB
import knf.kuma.directory.DirectoryService
import knf.kuma.download.DownloadManager
import knf.kuma.download.FileAccessHelper
import knf.kuma.pojos.*
import knf.kuma.queue.QueueManager
import knf.kuma.videoservers.ServersFactory
import kotlinx.android.synthetic.main.item_recents.view.*
import xdroid.toaster.Toaster.toast
import java.util.*

class RecentsAdapter internal constructor(private val fragment: Fragment, private val view: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val context: Context? = fragment.context
    private var list: MutableList<RecentObject> = ArrayList()
    private val dao = CacheDB.INSTANCE.favsDAO()
    private val animeDAO = CacheDB.INSTANCE.animeDAO()
    private val chaptersDAO = CacheDB.INSTANCE.seenDAO()
    private val recordsDAO = CacheDB.INSTANCE.recordsDAO()
    private val downloadsDAO = CacheDB.INSTANCE.downloadsDAO()
    private var isNetworkAvailable: Boolean = Network.isConnected

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 1)
            return AdCardItemHolder(parent)
        return ItemHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_recents, parent, false))
    }

    override fun getItemViewType(position: Int): Int {
        return noCrashLet { if (list[position] is AdRecentObject) 1 else 0 } ?: 1
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (context == null) return
        if (holder is AdCardItemHolder)
            noCrash { holder.loadAd(list[position] as? AdCallback) }
        else if (holder is ItemHolder) {
            holder.unsetObservers()
            val recentObject = noCrashLet { list[position] } ?: return
            holder.setState(isNetworkAvailable, recentObject.isChapterDownloaded || recentObject.isDownloading)
            PicassoSingle.get().load(PatternUtil.getCover(recentObject.aid)).into(holder.imageView)
            holder.setNew(recentObject.isNew)
            holder.setFav(dao.isFav(Integer.parseInt(recentObject.aid)))
            holder.setSeen(chaptersDAO.chapterIsSeen(recentObject.eid))
            dao.favObserver(Integer.parseInt(recentObject.aid)).distinct.observe(fragment, Observer { object1 -> holder.setFav(object1 != null) })
            holder.setChapterObserver(chaptersDAO.chapterSeen(recentObject.eid).distinct, fragment, Observer { chapter -> holder.setSeen(chapter != null) })
            holder.setDownloadObserver(downloadsDAO.getLiveByEid(recentObject.eid).distinct, fragment, Observer { downloadObject ->
                holder.setDownloadState(downloadObject)
                if (downloadObject == null) {
                    recentObject.downloadState = -8
                    recentObject.isDownloading = false
                } else {
                    recentObject.isDownloading = downloadObject.state == DownloadObject.DOWNLOADING || downloadObject.state == DownloadObject.PENDING || downloadObject.state == DownloadObject.PAUSED
                    recentObject.downloadState = downloadObject.state
                    val file = FileAccessHelper.getFile(recentObject.fileName)
                    recentObject.isChapterDownloaded = file.exists()
                    if (downloadObject.state == DownloadObject.DOWNLOADING || downloadObject.state == DownloadObject.PENDING)
                        holder.downIcon.setImageResource(R.drawable.ic_download)
                    else if (downloadObject.state == DownloadObject.PAUSED)
                        holder.downIcon.setImageResource(R.drawable.ic_pause_normal)
                }
                holder.setState(isNetworkAvailable, recentObject.isChapterDownloaded || recentObject.isDownloading)
            })
            holder.setCastingObserver(fragment, Observer { s ->
                if (recentObject.eid == s) {
                    holder.setCasting(true, recentObject.fileName)
                    holder.streaming.setOnClickListener { CastUtil.get().openControls() }
                } else {
                    holder.setCasting(false, recentObject.fileName)
                    holder.streaming.setOnClickListener {
                        if (recentObject.isChapterDownloaded || recentObject.isDownloading) {
                            MaterialDialog(context).safeShow {
                                message(text = "¿Eliminar el ${recentObject.chapter.toLowerCase(Locale.ENGLISH)} de ${recentObject.name}?")
                                positiveButton(text = "CONFIRMAR") {
                                    FileAccessHelper.delete(recentObject.fileName, true)
                                    DownloadManager.cancel(recentObject.eid)
                                    QueueManager.remove(recentObject.eid)
                                    recentObject.isChapterDownloaded = false
                                    holder.setState(isNetworkAvailable, false)
                                }
                                negativeButton(text = "CANCELAR")
                            }
                        } else {
                            holder.setLocked(true)
                            ServersFactory.start(context, recentObject.url, DownloadObject.fromRecent(recentObject), true, object : ServersFactory.ServersInterface {
                                override fun onFinish(started: Boolean, success: Boolean) {
                                    if (!started && success) {
                                        chaptersDAO.addChapter(SeenObject.fromRecent(recentObject))
                                        recordsDAO.add(RecordObject.fromRecent(recentObject))
                                        syncData {
                                            history()
                                            seen()
                                        }
                                    }
                                    holder.setLocked(false)
                                }

                                override fun onCast(url: String?) {
                                    CastUtil.get().play(view, CastMedia.create(recentObject, url))
                                    chaptersDAO.addChapter(SeenObject.fromRecent(recentObject))
                                    recordsDAO.add(RecordObject.fromRecent(recentObject))
                                    syncData {
                                        history()
                                        seen()
                                    }
                                    holder.setSeen(true)
                                    holder.setLocked(false)
                                }

                                override fun onProgressIndicator(boolean: Boolean) {
                                    doOnUI {
                                        if (boolean) {
                                            holder.progressBar.isIndeterminate = true
                                            holder.progressBarRoot.visibility = View.VISIBLE
                                        } else
                                            holder.progressBarRoot.visibility = View.GONE
                                    }
                                }

                                override fun getView(): View? {
                                    return view
                                }
                            })
                        }
                    }
                }
            })
            holder.title.text = recentObject.name
            holder.chapter.text = recentObject.chapter
            if (BuildConfig.BUILD_TYPE == "playstore") holder.layButtons.visibility = View.INVISIBLE
            holder.cardView.setOnClickListener {
                if (recentObject.animeObject != null) {
                    ActivityAnime.open(fragment, recentObject.animeObject, holder.imageView)
                } else {
                    if (BuildConfig.BUILD_TYPE == "playstore" && PrefsUtil.isDirectoryFinished) {
                        toast("Anime deshabilitado para esta version")
                    } else if (PrefsUtil.isFamilyFriendly && PrefsUtil.isDirectoryFinished) {
                        toast("Anime no familiar")
                    } else {
                        val animeObject = animeDAO.getByAid(recentObject.aid)
                        if (animeObject != null) {
                            ActivityAnime.open(fragment, animeObject, holder.imageView)
                        } else {
                            toast("Aún no esta en directorio!")
                            DirectoryService.run(context)
                        }
                    }
                }
            }
            holder.cardView.setOnLongClickListener {
                if (!chaptersDAO.chapterIsSeen(recentObject.eid)) {
                    chaptersDAO.addChapter(SeenObject.fromRecent(recentObject))
                    holder.animeOverlay.setSeen(seen = true, animate = true)
                } else {
                    chaptersDAO.deleteChapter(SeenObject.fromRecent(recentObject))
                    holder.animeOverlay.setSeen(seen = false, animate = true)
                }
                syncData { seen() }
                true
            }
            holder.download.setOnClickListener {
                val obj = downloadsDAO.getByEid(recentObject.eid)
                if (FileAccessHelper.canDownload(fragment) &&
                        !recentObject.isChapterDownloaded &&
                        !recentObject.isDownloading &&
                        recentObject.downloadState != DownloadObject.PENDING) {
                    holder.setLocked(true)
                    ServersFactory.start(context, recentObject.url, AnimeObject.WebInfo.AnimeChapter.fromRecent(recentObject), isStream = false, addQueue = false, serversInterface = object : ServersFactory.ServersInterface {
                        override fun onFinish(started: Boolean, success: Boolean) {
                            if (started) {
                                recentObject.isChapterDownloaded = true
                                holder.setState(isNetworkAvailable, true)
                            }
                            holder.setLocked(false)
                        }

                        override fun onCast(url: String?) {

                        }

                        override fun onProgressIndicator(boolean: Boolean) {
                            doOnUI {
                                if (boolean) {
                                    holder.progressBar.isIndeterminate = true
                                    holder.progressBarRoot.visibility = View.VISIBLE
                                } else
                                    holder.progressBarRoot.visibility = View.GONE
                            }
                        }

                        override fun getView(): View? {
                            return view
                        }
                    })
                } else if (recentObject.isChapterDownloaded && (obj == null || obj.state == DownloadObject.DOWNLOADING || obj.state == DownloadObject.COMPLETED)) {
                    chaptersDAO.addChapter(SeenObject.fromRecent(recentObject))
                    recordsDAO.add(RecordObject.fromRecent(recentObject))
                    syncData {
                        history()
                        seen()
                    }
                    holder.setSeen(true)
                    ServersFactory.startPlay(context, recentObject.epTitle, recentObject.fileName)
                } else {
                    toast("Aun no se está descargando")
                }
            }
            holder.download.setOnLongClickListener {
                val obj = downloadsDAO.getByEid(recentObject.eid)
                if (CastUtil.get().connected() &&
                        recentObject.isChapterDownloaded && (obj == null || obj.state == DownloadObject.COMPLETED)) {
                    chaptersDAO.addChapter(SeenObject.fromRecent(recentObject))
                    syncData { seen() }
                    CastUtil.get().play(view, CastMedia.create(recentObject))
                }
                true
            }
        }
    }

    private fun setOrientation(block: Boolean) {
        noCrash {
            if (block)
                (fragment.activity as? AppCompatActivity)?.requestedOrientation = when {
                    context?.resources?.getBoolean(R.bool.isLandscape) == true -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            else (fragment.activity as? AppCompatActivity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ItemHolder)
            holder.unsetObservers()
        super.onViewRecycled(holder)
    }

    internal fun updateList(list: MutableList<RecentObject>, updateListener: UpdateListener) {
        this.isNetworkAvailable = Network.isConnected
        val wasEmpty = this.list.isEmpty()
        this.list = list.distinctBy { it.eid } as MutableList<RecentObject>
        this.list.implAdsRecent()
        if (this.list.isNotEmpty())
            view.post {
                notifyDataSetChanged()
                if (wasEmpty)
                    updateListener.invoke()
            }
    }

    override fun getItemId(position: Int): Long {
        return noCrashLet { list[position].key.toLong() } ?: 0
    }

    override fun getItemCount(): Int {
        return list.size
    }

    inner class ItemHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardView: MaterialCardView = itemView.card
        val imageView: ImageView = itemView.img
        val title: TextView = itemView.title
        val chapter: TextView = itemView.chapter
        val streaming: Button = itemView.streaming
        val download: Button = itemView.download
        val animeOverlay: SeenAnimeOverlay = itemView.seenOverlay
        val downIcon: ImageView = itemView.down_icon
        private val newIcon: ImageView = itemView.new_icon
        private val favIcon: ImageView = itemView.fav_icon
        val progressBar: ProgressBar = itemView.progress
        val progressBarRoot: View = itemView.progress_root
        val layButtons: View = itemView.lay_buttons

        private var chapterLiveData: LiveData<SeenObject> = MutableLiveData()
        private var downloadLiveData: LiveData<DownloadObject> = MutableLiveData()

        private var chapterObserver: Observer<SeenObject>? = null
        private var downloadObserver: Observer<DownloadObject>? = null
        private var castingObserver: Observer<String>? = null

        fun setChapterObserver(chapterLiveData: LiveData<SeenObject>, owner: LifecycleOwner, observer: Observer<SeenObject>) {
            this.chapterLiveData = chapterLiveData
            this.chapterObserver = observer
            this.chapterLiveData.observe(owner, observer)
        }

        private fun unsetChapterObserver() {
            chapterObserver?.let {
                chapterLiveData.removeObserver(it)
                chapterObserver = null
            }
        }

        fun setDownloadObserver(downloadLiveData: LiveData<DownloadObject>, owner: LifecycleOwner, observer: Observer<DownloadObject>) {
            this.downloadLiveData = downloadLiveData
            this.downloadObserver = observer
            this.downloadLiveData.observe(owner, observer)
        }

        private fun unsetDownloadObserver() {
            downloadObserver?.let {
                downloadLiveData.removeObserver(it)
                downloadObserver = null
            }
        }

        fun setCastingObserver(owner: LifecycleOwner, observer: Observer<String>) {
            this.castingObserver = observer
            CastUtil.get().casting.observe(owner, observer)
        }

        private fun unsetCastingObserver() {
            castingObserver?.let {
                CastUtil.get().casting.removeObserver(it)
                castingObserver = null
            }
        }

        fun unsetObservers() {
            unsetChapterObserver()
            unsetDownloadObserver()
            unsetCastingObserver()
        }

        fun setNew(isNew: Boolean) {
            newIcon.post { newIcon.visibility = if (isNew) View.VISIBLE else View.GONE }
        }

        fun setFav(isFav: Boolean) {
            favIcon.post { favIcon.visibility = if (isFav) View.VISIBLE else View.GONE }
        }

        private fun setDownloaded(isDownloaded: Boolean) {
            downIcon.post { downIcon.visibility = if (isDownloaded) View.VISIBLE else View.GONE }
        }

        fun setSeen(seen: Boolean) {
            animeOverlay.setSeen(seen, false)
        }

        fun setLocked(locked: Boolean) {
            streaming.post { streaming.isEnabled = !locked }
            download.post { download.isEnabled = !locked }
            setOrientation(locked)
        }

        fun setCasting(casting: Boolean, file_name: String) {
            streaming.post { streaming.text = if (casting) "CAST" else if (FileAccessHelper.getFile(file_name).exists()) "ELIMINAR" else "STREAMING" }
        }

        @UiThread
        fun setState(isNetworkAvailable: Boolean, existFile: Boolean) {
            setDownloaded(existFile)
            streaming.post {
                streaming.text = if (existFile) "ELIMINAR" else "STREAMING"
                if (!existFile)
                    streaming.isEnabled = isNetworkAvailable
                else
                    streaming.isEnabled = true
            }
            download.post {
                download.isEnabled = isNetworkAvailable || existFile
                download.text = if (existFile) "REPRODUCIR" else "DESCARGA"
            }
        }

        fun setDownloadState(downloadObject: DownloadObject?) {
            progressBar.post {
                if (downloadObject != null && PrefsUtil.showProgress())
                    when (downloadObject.state) {
                        DownloadObject.PENDING -> {
                            progressBarRoot.visibility = View.VISIBLE
                            progressBar.isIndeterminate = true
                        }
                        DownloadObject.DOWNLOADING -> {
                            progressBarRoot.visibility = View.VISIBLE
                            progressBar.isIndeterminate = false
                            if (downloadObject.getEta() == -2L || PrefsUtil.downloaderType == 0) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                                    progressBar.setProgress(downloadObject.progress, true)
                                else
                                    progressBar.progress = downloadObject.progress
                                if (downloadObject.getEta() == -2L && PrefsUtil.downloaderType != 0)
                                    progressBar.secondaryProgress = 100
                            } else {
                                progressBar.progress = 0
                                progressBar.secondaryProgress = downloadObject.progress
                            }
                        }
                        DownloadObject.PAUSED -> {
                            progressBarRoot.visibility = View.VISIBLE
                            progressBar.isIndeterminate = false
                        }
                        else -> progressBarRoot.visibility = View.GONE
                    }
                else
                    progressBarRoot.visibility = View.GONE
            }
        }
    }
}

typealias UpdateListener = () -> Unit
