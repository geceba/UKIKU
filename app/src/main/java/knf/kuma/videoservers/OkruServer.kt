package knf.kuma.videoservers

import android.content.Context
import knf.kuma.commons.PatternUtil
import knf.kuma.videoservers.VideoServer.Names.OKRU
import org.json.JSONObject
import org.jsoup.Jsoup

class OkruServer(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("ok.ru")

    override val name: String
        get() = OKRU

    override val videoServer: VideoServer?
        get() {
            try {
                val downLink = PatternUtil.extractLink(baseLink)
                val eJson = Jsoup.connect(downLink).get().select("div[data-module='OKVideo']").first().attr("data-options")
                val cutJson = "{" + eJson.substring(eJson.lastIndexOf("\\\"videos"), eJson.indexOf(",\\\"metadataEmbedded")).replace("\\&quot;", "\"").replace("\\u0026", "&").replace("\\", "").replace("%3B", ";") + "}"
                val array = JSONObject(cutJson).getJSONArray("videos")
                val videoServer = VideoServer(OKRU)
                for (i in 0 until array.length()) {
                    val json = array.getJSONObject(i)
                    when (json.getString("name")) {
                        "hd" -> videoServer.addOption(Option(name, "HD", json.getString("url")))
                        "sd" -> videoServer.addOption(Option(name, "SD", json.getString("url")))
                        "low" -> videoServer.addOption(Option(name, "LOW", json.getString("url")))
                        "lowest" -> videoServer.addOption(Option(name, "LOWEST", json.getString("url")))
                        "mobile" -> videoServer.addOption(Option(name, "MOBILE", json.getString("url")))
                    }
                }
                return videoServer
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }

        }
}
