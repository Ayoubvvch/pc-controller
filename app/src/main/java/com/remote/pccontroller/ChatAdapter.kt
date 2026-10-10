package com.remote.pccontroller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

class ChatAdapter(
    private val messages: List<ChatMessage>,
    private val client: OkHttpClient,
    private val onImageClick: ((String) -> Unit)? = null,
    private val onVolumeChanged: ((Int) -> Unit)? = null,
    private val onMuteClick: (() -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 1
        private const val TYPE_PC = 2
        private val imageMemoryCache = LruCache<String, Bitmap>(16 * 1024 * 1024)
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) TYPE_USER else TYPE_PC
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            val view = inflater.inflate(R.layout.item_message_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_message_pc, parent, false)
            PcViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = messages[position]
        if (holder is UserViewHolder) {
            holder.tvMessage.text = item.message
            holder.tvTime.text = item.time
        } else if (holder is PcViewHolder) {
            holder.tvMessage.text = item.message
            holder.tvTime.text = item.time

            // Screenshot Handling
            val imageUrl = item.imageUrl
            if (!imageUrl.isNullOrEmpty()) {
                holder.layoutImageContainer.visibility = View.VISIBLE
                holder.ivScreenshot.tag = imageUrl

                val cached = imageMemoryCache.get(imageUrl)
                if (cached != null) {
                    holder.ivScreenshot.setImageBitmap(cached)
                    holder.pbImageLoading.visibility = View.GONE
                } else {
                    holder.ivScreenshot.setImageDrawable(null)
                    holder.pbImageLoading.visibility = View.VISIBLE

                    val req = Request.Builder().url(imageUrl).build()
                    client.newCall(req).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            holder.ivScreenshot.post {
                                if (holder.ivScreenshot.tag == imageUrl) {
                                    holder.pbImageLoading.visibility = View.GONE
                                }
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            if (response.isSuccessful) {
                                val bytes = response.body?.bytes()
                                response.close()
                                if (bytes != null) {
                                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    if (bitmap != null) {
                                        imageMemoryCache.put(imageUrl, bitmap)
                                        holder.ivScreenshot.post {
                                            if (holder.ivScreenshot.tag == imageUrl) {
                                                holder.ivScreenshot.setImageBitmap(bitmap)
                                                holder.pbImageLoading.visibility = View.GONE
                                            }
                                        }
                                        return
                                    }
                                }
                            }
                            holder.ivScreenshot.post {
                                if (holder.ivScreenshot.tag == imageUrl) {
                                    holder.pbImageLoading.visibility = View.GONE
                                }
                            }
                        }
                    })
                }

                holder.ivScreenshot.setOnClickListener {
                    onImageClick?.invoke(imageUrl)
                }
            } else {
                holder.layoutImageContainer.visibility = View.GONE
                holder.ivScreenshot.setOnClickListener(null)
            }

            // Interactive Volume Control Handling
            if (item.isVolumeControl) {
                holder.layoutVolumeControl.visibility = View.VISIBLE
                holder.tvVolumePercent.text = "${item.volumeLevel}%"
                holder.sbVolume.progress = item.volumeLevel

                // Prevent RecyclerView vertical scrolling from intercepting horizontal SeekBar drag
                holder.sbVolume.setOnTouchListener { v, _ ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }

                holder.sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            item.volumeLevel = progress
                            holder.tvVolumePercent.text = "$progress%"
                            onVolumeChanged?.invoke(progress)
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        val progress = seekBar?.progress ?: item.volumeLevel
                        item.volumeLevel = progress
                        holder.tvVolumePercent.text = "$progress%"
                        onVolumeChanged?.invoke(progress)
                    }
                })

                val applyPreset = { vol: Int ->
                    item.volumeLevel = vol
                    holder.sbVolume.progress = vol
                    holder.tvVolumePercent.text = "$vol%"
                    onVolumeChanged?.invoke(vol)
                }

                holder.btnVolMute.setOnClickListener { onMuteClick?.invoke() }
                holder.btnVol15.setOnClickListener { applyPreset(15) }
                holder.btnVol30.setOnClickListener { applyPreset(30) }
                holder.btnVol50.setOnClickListener { applyPreset(50) }
                holder.btnVol75.setOnClickListener { applyPreset(75) }
                holder.btnVol100.setOnClickListener { applyPreset(100) }
            } else {
                holder.layoutVolumeControl.visibility = View.GONE
                holder.sbVolume.setOnSeekBarChangeListener(null)
                holder.sbVolume.setOnTouchListener(null)
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        val tvTime: TextView = itemView.findViewById(R.id.tvTime)
    }

    class PcViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        val tvSender: TextView = itemView.findViewById(R.id.tvSender)
        val layoutImageContainer: View = itemView.findViewById(R.id.layoutImageContainer)
        val ivScreenshot: ImageView = itemView.findViewById(R.id.ivScreenshot)
        val pbImageLoading: ProgressBar = itemView.findViewById(R.id.pbImageLoading)
        val layoutVolumeControl: View = itemView.findViewById(R.id.layoutVolumeControl)
        val tvVolumePercent: TextView = itemView.findViewById(R.id.tvVolumePercent)
        val sbVolume: SeekBar = itemView.findViewById(R.id.sbVolume)
        val btnVolMute: TextView = itemView.findViewById(R.id.btnVolMute)
        val btnVol15: TextView = itemView.findViewById(R.id.btnVol15)
        val btnVol30: TextView = itemView.findViewById(R.id.btnVol30)
        val btnVol50: TextView = itemView.findViewById(R.id.btnVol50)
        val btnVol75: TextView = itemView.findViewById(R.id.btnVol75)
        val btnVol100: TextView = itemView.findViewById(R.id.btnVol100)
    }
}
