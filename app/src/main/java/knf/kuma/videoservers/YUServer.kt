package knf.kuma.videoservers

import android.content.Context
import knf.kuma.commons.PatternUtil
import knf.kuma.videoservers.VideoServer.Names.YOURUPLOAD
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class YUServer(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("yourupload.com")

    override val name: String
        get() = YOURUPLOAD

    override val videoServer: VideoServer?
        get() {
            val yuLink = PatternUtil.extractLink(baseLink)
            try {
                val videoLink = PatternUtil.getYUvideoLink(Jsoup.connect(yuLink).get().html())
                val client = OkHttpClient().newBuilder().followRedirects(false).build()
                val request = Request.Builder()
                        .url(videoLink)
                        .addHeader("Referer", yuLink)
                        .build()
                val response = client.newCall(request).execute()
                val refVideoLink = response.header("Location")
                response.close()
                val headers = Headers()
                headers.addHeader("Range", "bytes=0-")
                headers.addHeader("Referer", yuLink)
                return VideoServer(YOURUPLOAD, Option(name, null, refVideoLink, headers))
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }

        }
}