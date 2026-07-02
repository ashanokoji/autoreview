package com.example.autoreview.ui

import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.autoreview.R
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView

@Composable
fun BannerAdView(adUnitId: String) {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                this.adUnitId = adUnitId
                loadAd(AdRequest.Builder().build())
            }
        }
    )
}

@Composable
fun NativeAdViewComposable(adUnitId: String) {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { context ->
            val inflater = LayoutInflater.from(context)
            val adView = inflater.inflate(R.layout.native_ad_layout, null, false) as NativeAdView
            
            val builder = AdLoader.Builder(context, adUnitId)
            builder.forNativeAd { nativeAd ->
                populateNativeAdView(nativeAd, adView)
            }
            
            val adLoader = builder.withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    // Handle failure
                }
            }).withNativeAdOptions(NativeAdOptions.Builder().build()).build()
            
            adLoader.loadAd(AdRequest.Builder().build())
            
            adView
        }
    )
}

private fun populateNativeAdView(nativeAd: NativeAd, adView: NativeAdView) {
    adView.mediaView = adView.findViewById(R.id.ad_media)
    adView.headlineView = adView.findViewById(R.id.ad_headline)
    adView.bodyView = adView.findViewById(R.id.ad_body)
    adView.callToActionView = adView.findViewById(R.id.ad_call_to_action)
    adView.iconView = adView.findViewById(R.id.ad_app_icon)
    
    (adView.headlineView as TextView).text = nativeAd.headline
    
    if (nativeAd.body == null) {
        adView.bodyView?.visibility = android.view.View.INVISIBLE
    } else {
        adView.bodyView?.visibility = android.view.View.VISIBLE
        (adView.bodyView as TextView).text = nativeAd.body
    }
    
    if (nativeAd.callToAction == null) {
        adView.callToActionView?.visibility = android.view.View.INVISIBLE
    } else {
        adView.callToActionView?.visibility = android.view.View.VISIBLE
        (adView.callToActionView as Button).text = nativeAd.callToAction
    }
    
    if (nativeAd.icon == null) {
        adView.iconView?.visibility = android.view.View.GONE
    } else {
        (adView.iconView as ImageView).setImageDrawable(nativeAd.icon?.drawable)
        adView.iconView?.visibility = android.view.View.VISIBLE
    }
    
    adView.setNativeAd(nativeAd)
}
