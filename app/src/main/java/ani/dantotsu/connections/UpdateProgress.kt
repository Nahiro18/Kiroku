package ani.dantotsu.connections

import ani.dantotsu.R
import ani.dantotsu.Refresh
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.connections.mal.MAL
import ani.dantotsu.currContext
import ani.dantotsu.media.Media
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun updateProgress(media: Media, number: String) {
    val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
    if (!incognito) {
        if (Anilist.userid != null) {
            CoroutineScope(Dispatchers.IO).launch {
                val a = number.toFloatOrNull()?.toInt()
                if ((a ?: 0) > (media.userProgress ?: -1)) {
                    val status = if (media.userStatus == "REPEATING") "REPEATING" else "CURRENT"
                    val pushed = Anilist.mutation.editList(
                        media.id,
                        a,
                        status = status
                    )
                    if (pushed) {
                        MAL.query.editList(
                            media.idMAL,
                            media.anime != null,
                            a, null,
                            status
                        )
                        toast(currContext()?.getString(R.string.setting_progress, a))
                    } else if (a != null) {
                        KirokuProgressQueue.enqueue(
                            KirokuQueuedProgress(
                                mediaId = media.id,
                                malId = media.idMAL,
                                anime = media.anime != null,
                                progress = a,
                                status = status,
                            )
                        )
                        toast(currContext()?.getString(R.string.progress_queued, a))
                    }
                    KirokuProgressQueue.sync()
                }
                media.userProgress = a
                Refresh.all()
            }
        } else {
            toast(currContext()?.getString(R.string.login_anilist_account))
        }
    } else {
        toast("Sneaky sneaky :3")
    }
}
