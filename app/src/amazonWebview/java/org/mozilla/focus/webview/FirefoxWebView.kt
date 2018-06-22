/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.webview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.annotation.VisibleForTesting
import android.util.AttributeSet
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.mozilla.focus.ext.deleteData
import org.mozilla.focus.iwebview.IWebView
import org.mozilla.focus.session.Session
import org.mozilla.focus.utils.UrlUtils

private val uiHandler = Handler(Looper.getMainLooper())

/**
 * An IWebView implementation using WebView.
 *
 * Initialization for this class should primarily occur in WebViewProvider,
 * which is visible by the main code base and constructs this class.
 */
@Suppress("ViewConstructor") // We only construct this in code.
internal class FirefoxWebView(
        context: Context, attrs: AttributeSet,
        private val client: FocusWebViewClient,
        private val chromeClient: FirefoxWebChromeClient
) : NestedWebView(context, attrs), IWebView {

    private val focusedDOMElement = FocusedDOMElementCache(this)

    @get:VisibleForTesting
    override var callback: IWebView.Callback? = null
        set(callback) {
            field = callback
            client.setCallback(callback)
            chromeClient.callback = callback
            linkHandler.setCallback(callback)
        }

    // Init for link handler must occur here if we want immutability because they have cyclic references.
    private val linkHandler = LinkHandler(this)
    init {
        setOnLongClickListener(linkHandler)
        isLongClickable = true
    }

    override fun restoreWebViewState(session: Session) {
        val stateData = session.webViewState

        val backForwardList = if (stateData != null) super.restoreState(stateData) else null
        val desiredURL = session.url.value

        client.restoreState(stateData)
        client.notifyCurrentURL(desiredURL)

        // Pages are only added to the back/forward list when loading finishes. If a new page is
        // loading when the Activity is paused/killed, then that page won't be in the list,
        // and needs to be restored separately to the history list. We detect this by checking
        // whether the last fully loaded page (getCurrentItem()) matches the last page that the
        // WebView was actively loading (which was retrieved during onSaveInstanceState():
        // WebView.getUrl() always returns the currently loading or loaded page).
        // If the app is paused/killed before the initial page finished loading, then the entire
        // list will be null - so we need to additionally check whether the list even exists.
        if (backForwardList != null && backForwardList.currentItem.url == desiredURL) {
            // restoreState doesn't actually load the current page, it just restores navigation history,
            // so we also need to explicitly reload in this case:
            reload()
        } else {
            loadUrl(desiredURL)
        }
    }

    override fun saveWebViewState(session: Session) {
        // We store the actual state into another bundle that we will keep in memory as long as this
        // browsing session is active. The data that WebView stores in this bundle is too large for
        // Android to save and restore as part of the state bundle.
        val stateData = Bundle()

        super.saveState(stateData)
        client.saveState(this, stateData)

        session.saveWebViewState(stateData)
    }

    override fun setBlockingEnabled(enabled: Boolean) {
        client.isBlockingEnabled = enabled
        this.callback?.onBlockingStateChanged(enabled)
    }

    override fun goBack() {
        super.goBack()
        // onHideCustomView calls through to fullscreen callbacks.
        chromeClient.onHideCustomView()
    }

    override fun goForward() {
        super.goForward()
        // onHideCustomView calls through to fullscreen callbacks.
        chromeClient.onHideCustomView()
    }

    @Suppress("DEPRECATION") // shouldOverrideUrlLoading deprecated in 24 but we support 22.
    override fun loadUrl(url: String) {
        // We need to check external URL handling here - shouldOverrideUrlLoading() is only
        // called by webview when clicking on a link, and not when opening a new page for the
        // first time using loadUrl().
        if (!client.shouldOverrideUrlLoading(this, url)) {
            super.loadUrl(url, mapOf("X-Requested-With" to ""))
        }

        client.notifyCurrentURL(url)
    }

    override fun evalJS(js: String) {
        evaluateJavascript(js, null)
    }

    override fun cleanup() {
        this.deleteData()
    }

    override fun takeScreenshot(): Bitmap {
        // Under the hood, createBitmap may create a new reference to the existing Bitmap so
        // it's efficient: we don't have to copy the existing Bitmap or GC it on destroy.
        buildDrawingCache()
        val outBitmap = Bitmap.createBitmap(drawingCache)
        destroyDrawingCache()
        return outBitmap
    }

    override fun onOverlayPreSetVisibility(willOverlayBeVisible: Boolean) {
        // We cache when the overlay is opened but actions on the overlay can clear the DOM and
        // any focused element cache (e.g. reload) so we need to cache again before it's hidden:
        // see FocusedDOMElementCache for details.
        if (!willOverlayBeVisible) {
            focusedDOMElement.cache()
        }
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)

        // For why we're doing this, see FocusedDOMElementCache.
        if (!focused) {
            // Any views (like HomeTileGridNavigation) that may clear the cache, e.g. by
            // reloading the page, are required to handle their own caching. Here we'll handle
            // cases where the page cache isn't cleared.
            focusedDOMElement.cache()
        } else {
            // Trying to restore immediately doesn't work - perhaps the WebView hasn't actually
            // received focus yet? Posting to the end of the UI queue seems to solve the problem.
            uiHandler.post { focusedDOMElement.restore() }
        }
    }

    override fun scrollByClamped(vx: Int, vy: Int) {
        // This is not a true clamp: it can only stop us from
        // continuing to scroll if we've already overscrolled.
        val scrollX = clampScroll(vx) { canScrollHorizontally(it) }
        val scrollY = clampScroll(vy) { canScrollVertically(it) }

        super.scrollBy(scrollX, scrollY)
    }
}

private inline fun clampScroll(scroll: Int, canScroll: (direction: Int) -> Boolean) = if (scroll != 0 && canScroll(scroll)) {
    scroll
} else {
    0
}

// todo: move into WebClients file with FocusWebViewClient.
internal class FirefoxWebChromeClient : WebChromeClient() {
    var callback: IWebView.Callback? = null

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        callback?.let { callback ->
            // This is the earliest point where we might be able to confirm a redirected
            // URL: we don't necessarily get a shouldInterceptRequest() after a redirect,
            // so we can only check the updated url in onProgressChanges(), or in onPageFinished()
            // (which is even later).
            val viewURL = view!!.url
            if (!UrlUtils.isInternalErrorURL(viewURL) && viewURL != null) {
                callback.onURLChanged(viewURL)
            }
            callback.onProgress(newProgress)
        }
    }

    override fun onShowCustomView(view: View?, webviewCallback: WebChromeClient.CustomViewCallback?) {
        val fullscreenCallback = object : IWebView.FullscreenCallback {
            override fun fullScreenExited() {
                webviewCallback?.onCustomViewHidden()
            }
        }

        callback?.onEnterFullScreen(fullscreenCallback, view)
    }

    override fun onHideCustomView() {
        callback?.onExitFullScreen()
    }
}
