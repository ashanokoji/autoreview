package com.example.autoreview.ui

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.unity3d.mediation.LevelPlayAdSize
import com.unity3d.mediation.banner.LevelPlayBannerAdView
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.example.autoreview.R
import com.ironsource.mediationsdk.ads.nativead.LevelPlayMediaView
import com.ironsource.mediationsdk.ads.nativead.LevelPlayNativeAd
import com.ironsource.mediationsdk.ads.nativead.LevelPlayNativeAdListener
import com.ironsource.mediationsdk.ads.nativead.NativeAdLayout
import com.ironsource.mediationsdk.adunit.adapter.utility.AdInfo
import com.ironsource.mediationsdk.logger.IronSourceError

@Composable
fun BannerAdView(adUnitId: String) {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            val adSize = LevelPlayAdSize.BANNER
            val adConfig = LevelPlayBannerAdView.Config.Builder()
                .setAdSize(adSize)
                .build()
            val levelPlayBanner = LevelPlayBannerAdView(context, adUnitId, adConfig)
            levelPlayBanner.loadAd()
            levelPlayBanner
        }
    )
}

@Composable
fun NativeAdViewComposable(adUnitId: String) {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            val inflater = LayoutInflater.from(context)
            val nativeAdLayout = inflater.inflate(R.layout.native_ad_layout, null, false) as NativeAdLayout
            
            val nativeAd = LevelPlayNativeAd.Builder()
                .withPlacementName(adUnitId)
                .withListener(object : LevelPlayNativeAdListener {
                    override fun onAdLoaded(ad: LevelPlayNativeAd?, adInfo: AdInfo?) {
                        if (ad == null) return
                        val titleView = nativeAdLayout.findViewById<TextView>(R.id.ad_headline)
                        val iconView = nativeAdLayout.findViewById<ImageView>(R.id.ad_app_icon)
                        val mediaView = nativeAdLayout.findViewById<LevelPlayMediaView>(R.id.ad_media)
                        val bodyView = nativeAdLayout.findViewById<TextView>(R.id.ad_body)
                        val ctaView = nativeAdLayout.findViewById<Button>(R.id.ad_call_to_action)

                        val title = ad.title
                        if (title != null) titleView.text = title

                        val body = ad.body
                        if (body != null) {
                            bodyView.text = body
                            bodyView.visibility = android.view.View.VISIBLE
                        } else {
                            bodyView.visibility = android.view.View.GONE
                        }

                        val cta = ad.callToAction
                        if (cta != null) ctaView.text = cta

                        val icon = ad.icon
                        if (icon?.drawable != null) {
                            iconView.setImageDrawable(icon.drawable)
                            iconView.visibility = android.view.View.VISIBLE
                        } else {
                            iconView.visibility = android.view.View.GONE
                        }

                        nativeAdLayout.setTitleView(titleView)
                        nativeAdLayout.setIconView(iconView)
                        nativeAdLayout.setMediaView(mediaView)
                        nativeAdLayout.setBodyView(bodyView)
                        nativeAdLayout.setCallToActionView(ctaView)

                        nativeAdLayout.registerNativeAdViews(ad)
                    }

                    override fun onAdLoadFailed(ad: LevelPlayNativeAd?, error: IronSourceError?) {}
                    override fun onAdImpression(ad: LevelPlayNativeAd?, adInfo: AdInfo?) {}
                    override fun onAdClicked(ad: LevelPlayNativeAd?, adInfo: AdInfo?) {}
                })
                .build()

            nativeAd.loadAd()
            nativeAdLayout
        }
    )
}
