package com.xycz.simple_live_tv.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.xycz.simple_live_tv.R
import com.xycz.simple_live_tv.data.Sites
import com.xycz.simple_live_tv.databinding.ItemAnchorBinding
import com.xycz.simple_live_tv.databinding.ItemRoomBinding
import com.xycz.simple_live_tv.util.formatOnline
import com.xycz.simple_live_tv.util.tvFocus

/** 直播间卡片列表；滚动到接近末尾时回调 [onLoadMore] */
class RoomAdapter(
    private val onClick: (RoomCard) -> Unit,
    private val onLoadMore: (() -> Unit)? = null,
) : RecyclerView.Adapter<RoomAdapter.Holder>() {
    private val items = mutableListOf<RoomCard>()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<RoomCard>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<RoomCard>) {
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    val size get() = items.size

    class Holder(val b: ItemRoomBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val b = ItemRoomBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        // 获得焦点时标题跑马灯（TextView 只在文字放不下时滚动）
        b.root.tvFocus { b.title.isSelected = it }
        return Holder(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.b.cover.load(item.cover) {
            size(320, 180)
            placeholder(R.drawable.bg_cover_placeholder)
            error(R.drawable.bg_cover_placeholder)
        }
        holder.b.title.text = item.title
        holder.b.anchor.text = item.userName
        holder.b.online.text = "🔥 ${formatOnline(item.online)}"
        holder.b.online.visibility = if (item.online > 0) View.VISIBLE else View.GONE
        holder.b.root.setOnClickListener { onClick(item) }
        if (position >= items.size - 6) onLoadMore?.invoke()
    }
}

data class RoomCard(
    val siteId: String,
    val roomId: String,
    val title: String,
    val cover: String,
    val userName: String,
    val online: Long,
)

data class AnchorCard(
    val siteId: String,
    val roomId: String,
    val userName: String,
    val avatar: String,
    /** 0=未知 1=未开播 2=直播中；null 表示不显示状态 */
    val liveStatus: Int?,
    val id: String = "${siteId}_$roomId",
)

class AnchorAdapter(
    private val onClick: (AnchorCard) -> Unit,
    private val onLoadMore: (() -> Unit)? = null,
) : RecyclerView.Adapter<AnchorAdapter.Holder>() {
    private val items = mutableListOf<AnchorCard>()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<AnchorCard>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<AnchorCard>) {
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    val size get() = items.size

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) = items[position].id.hashCode().toLong()

    class Holder(val b: ItemAnchorBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val b = ItemAnchorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        b.root.tvFocus()
        return Holder(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.b.avatar.load(item.avatar) {
            size(96, 96)
            transformations(CircleCropTransformation())
            placeholder(R.drawable.bg_cover_placeholder)
            error(R.drawable.bg_cover_placeholder)
        }
        holder.b.name.text = item.userName
        holder.b.siteLogo.setImageResource(Sites[item.siteId].logo)
        val status = holder.b.status
        when (item.liveStatus) {
            null -> status.text = Sites[item.siteId].name
            2 -> {
                status.text = "直播中"
                status.setTextColor(status.context.getColor(R.color.live_green))
            }
            1 -> {
                status.text = "未开播"
                status.setTextColor(status.context.getColorStateList(R.color.subtext_focus))
            }
            else -> {
                status.text = "读取中"
                status.setTextColor(status.context.getColorStateList(R.color.subtext_focus))
            }
        }
        holder.b.root.setOnClickListener { onClick(item) }
        if (position >= items.size - 6) onLoadMore?.invoke()
    }
}
