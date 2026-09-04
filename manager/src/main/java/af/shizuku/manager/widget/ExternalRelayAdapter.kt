package af.shizuku.manager.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.manager.R
import com.google.android.material.button.MaterialButton

/**
 * Adapter for the external relay authorization screen ([af.shizuku.manager.settings.ExternalRelayActivity]).
 *
 * Today it lists the Scene relay card plus a placeholder card signalling that more relayed apps
 * are coming; future apps can be appended to [items] without touching the UI shell.
 */
class ExternalRelayAdapter(
    private val context: Context,
    private val onActivateScene: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_SCENE = 0
        private const val TYPE_PLACEHOLDER = 1
    }

    /** Ordered list of relay entries; extend this when adding new relayed apps. */
    val items = mutableListOf<Int>()

    init {
        items.add(TYPE_SCENE)
        items.add(TYPE_PLACEHOLDER)
    }

    override fun getItemViewType(position: Int): Int = items[position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SCENE -> SceneViewHolder(
                inflater.inflate(R.layout.item_external_relay_scene, parent, false)
            )
            else -> PlaceholderViewHolder(
                inflater.inflate(R.layout.item_external_relay_placeholder, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val vh = holder) {
            is SceneViewHolder -> vh.bind()
            is PlaceholderViewHolder -> vh.bind()
        }
    }

    override fun getItemCount(): Int = items.size

    inner class SceneViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val statusText: TextView = itemView.findViewById(R.id.status_text)
        private val actionButton: MaterialButton = itemView.findViewById(R.id.action_button)

        fun bind() {
            actionButton.setOnClickListener {
                statusText.text = context.getString(R.string.external_relay_scene_activating)
                statusText.visibility = android.view.View.VISIBLE
                onActivateScene()
            }
        }
    }

    inner class PlaceholderViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        fun bind() {
            // Static placeholder card; nothing to bind today.
        }
    }
}
