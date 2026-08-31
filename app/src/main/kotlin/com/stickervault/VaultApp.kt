package com.stickervault

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder

/**
 * Registers Coil's ImageDecoderDecoder so animated WebP stickers actually
 * animate in the previews instead of showing a frozen first frame. Being able
 * to tell an animated sticker from a static one at a glance matters, because
 * they are packed separately and repaired differently.
 */
class VaultApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(ImageDecoderDecoder.Factory()) }
            .build()
}
