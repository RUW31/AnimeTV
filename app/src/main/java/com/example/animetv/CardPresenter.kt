package com.example.animetv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide

/** Póster de anime. La franja inferior se pinta del color de la marca al enfocarla. */
class CardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val ctx = parent.context
        val normal = ContextCompat.getColor(ctx, R.color.card_info)
        val focused = ContextCompat.getColor(ctx, R.color.brand)
        val res = parent.resources

        val card = object : ImageCardView(ctx) {
            override fun setSelected(selected: Boolean) {
                setInfoAreaBackgroundColor(if (selected) focused else normal)
                super.setSelected(selected)
            }
        }
        card.isFocusable = true
        card.isFocusableInTouchMode = true
        card.setInfoAreaBackgroundColor(normal)
        card.setMainImageDimensions(
            res.getDimensionPixelSize(R.dimen.card_width),
            res.getDimensionPixelSize(R.dimen.card_height)
        )
        card.setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)
        return Presenter.ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        val card = viewHolder.view as ImageCardView
        val data = item as? CardItem ?: return
        card.titleText = data.title
        card.contentText = data.subtitle
        val imageView = card.mainImageView ?: return

        if (data.image.isBlank()) {
            imageView.setImageResource(R.drawable.card_placeholder)
            return
        }
        Glide.with(card)
            .load(imageModel(data.image))
            .placeholder(R.drawable.card_placeholder)
            .error(R.drawable.card_placeholder)
            .centerCrop()
            .into(imageView)
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
        val card = viewHolder.view as ImageCardView
        card.mainImageView?.let { Glide.with(card).clear(it) }
        card.mainImage = null
    }
}

/** Tarjetas de menú (Biblioteca, Buscar, géneros). */
class MenuPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder =
        Presenter.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.card_action, parent, false))

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        val menu = item as? MenuCard ?: return
        viewHolder.view.findViewById<ImageView>(R.id.action_icon).setImageResource(menu.icon)
        viewHolder.view.findViewById<TextView>(R.id.action_label).text = menu.label
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) = Unit
}

/** Tarjeta de episodio: número grande, barra de progreso y marca de "visto". */
class EpisodePresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder =
        Presenter.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.card_episode, parent, false))

    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
        val ep = item as? Episode ?: return
        val v = viewHolder.view
        v.findViewById<TextView>(R.id.ep_number).text = ep.number?.toString() ?: "▶"
        v.findViewById<TextView>(R.id.ep_title).text = ep.title

        val progress = Store.progress(ep.url)
        val bar = v.findViewById<ProgressBar>(R.id.ep_progress)
        bar.isVisible = progress != null && !progress.finished && progress.fraction > 0.01f
        bar.progress = ((progress?.fraction ?: 0f) * 100).toInt()
        v.findViewById<View>(R.id.ep_watched).isVisible = progress?.finished == true
    }

    override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) = Unit
}
