package af.shizuku.manager.management

import android.content.pm.PackageInfo
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import af.shizuku.manager.Helps
import af.shizuku.manager.R
import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.databinding.AppListEmptyBinding
import af.shizuku.manager.databinding.AppListItemBinding
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.utils.AppIconCache
import af.shizuku.manager.utils.ShizukuSystemApis
import af.shizuku.manager.utils.UserHandleCompat
import rikka.html.text.HtmlCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku

class EmptyViewHolder(private val binding: AppListEmptyBinding) : BaseViewHolder<Any>(binding.root) {

    companion object {
        @JvmField
        val CREATOR = Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? -> EmptyViewHolder(AppListEmptyBinding.inflate(inflater, parent, false)) }
    }

}
