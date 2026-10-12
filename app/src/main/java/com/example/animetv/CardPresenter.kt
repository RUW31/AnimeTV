package com.example.animetv

import android.view.ViewGroup
import android.widget.ImageView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders

/** Tarjeta de póster para animes y episodios. Leanback se encarga del foco y el zoom con el D-pad. */
class CardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
        val res = parent.resources
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(
                res.getDimensionPixelSize(R.dimen.card_width),
                res.getDimensionPixelSize(R.dimen.card_height)
            )
            setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)
        }
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
        // Muchos sitios bloquean imágenes sin Referer (anti-hotlinking).
        val model = GlideUrl(
            data.image,
            LazyHeaders.Builder().addHeader("Referer", SiteConfig.BASE_URL).build()
        )
        Glide.with(card)
            .load(model)
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
