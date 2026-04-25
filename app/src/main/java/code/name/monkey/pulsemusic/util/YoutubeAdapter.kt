package code.name.monkey.pulsemusic.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.appthemehelper.ThemeStore
import code.name.monkey.pulsemusic.R
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject

class YoutubeAdapter(
    private val results: List<JSONObject>,
    private val onItemClick: (videoId: String, title: String, thumbnail: String) -> Unit
) : RecyclerView.Adapter<YoutubeAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // XML'deki yeni ID'lerle eşleştiriyoruz
        val cardView: MaterialCardView = view.findViewById(R.id.cardView)
        val thumbnail: ImageView = view.findViewById(R.id.image)
        val accentColor = ThemeStore.accentColor(itemView.context)
        val titleTextView: TextView = view.findViewById(R.id.titleTextView)
        val dimmedColor = (accentColor and 0x00FFFFFF) or 0x40000000
        val artistTextView: TextView = view.findViewById(R.id.artistTextView)
        val playsTextView: TextView = view.findViewById(R.id.playsTextView)
        val moreOptions: ImageView = view.findViewById(R.id.moreOptions)

        fun bind(
            item: JSONObject,
            onItemClick: (String, String, String) -> Unit,
            accentColor: Int
        ) {
            val videoId = item.optString("videoId", "")
            val rawTitle = item.optString("title", "Bilinmeyen Video").clean()
            val thumbUrl = item.optString("thumbnail", "")

            // 📺 MÜZİK PARÇALAMA MOTORU
            // "Sanatçı - Şarkı" formatını ayırıyoruz
            val artistName: String
            val songName: String

            if (rawTitle.contains("-")) {
                val parts = rawTitle.split("-")
                artistName = parts[0].trim()
                songName = parts.drop(1).joinToString("-").trim()
            } else {
                artistName = "YouTube"
                songName = rawTitle
            }

            // 🎨 BUKALEMUN TEMA UYGULAMASI
            // Kartın rengini senin seçtiğin o canlı tema rengi yapıyoruz
            cardView.setCardBackgroundColor(ColorStateList.valueOf(dimmedColor))

            // Yazıları mühürle
            titleTextView.text = songName
            artistTextView.text = artistName
            // Alt bilgi kısmına kanal adı ve sabit bir metin ekledik
            playsTextView.text = "YouTube • Pulse"

            // ⚪ RENK KONTRASTI
            // Tema rengi ne olursa olsun yazıların okunması için beyaz/şeffaf beyaz kullanıyoruz
            titleTextView.setTextColor(accentColor)
            artistTextView.setTextColor(Color.WHITE)
            artistTextView.alpha = 0.8f
            playsTextView.setTextColor(Color.WHITE)
            playsTextView.alpha = 0.6f
            moreOptions.setColorFilter(Color.WHITE)

            // Kapak resmi (Yumuşak geçişle)
            Glide.with(itemView.context)
                .load(thumbUrl)
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.default_audio_art)
                .into(thumbnail)

            itemView.setOnClickListener {
                if (videoId.isNotEmpty()) {
                    onItemClick(videoId, rawTitle, thumbUrl)
                }
            }
        }

        // Yardımcı fonksiyon: Kirli karakterleri temizler
        private fun String.clean(): String {
            return this.replace("\\u0026", "&")
                .replace("&amp;", "&")
                .replace("\\u0027", "'")
                .replace("&quot;", "\"")
                .replace("\\u0022", "\"")
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_list_youtube, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // 🎨 pulsemusic'in o anki canlı renk mühürünü alıp bind'a gönderiyoruz
        val accentColor = ThemeStore.accentColor(holder.itemView.context)
        holder.bind(results[position], onItemClick, accentColor)
    }

    override fun getItemCount() = results.size
}