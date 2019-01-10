/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.architecture

import android.arch.lifecycle.ViewModel
import android.arch.lifecycle.ViewModelProvider
import org.mozilla.focus.MainActivityViewModel
import org.mozilla.focus.browser.BrowserViewModel
import org.mozilla.focus.home.NavigationOverlayViewModel
import org.mozilla.focus.toolbar.BrowserAppBarLayoutViewModel
import org.mozilla.focus.toolbar.ToolbarViewModel
import org.mozilla.focus.widget.ServiceLocator

/**
 * Instantiates [ViewModel] instances with the appropriate dependencies.
 */
class ViewModelFactory(
    private val serviceLocator: ServiceLocator
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST") // necessary to get generic types to match, unfortunately.
    override fun <T : ViewModel?> create(modelClass: Class<T>): T = with(serviceLocator) {
        when (modelClass) {
            MainActivityViewModel::class.java -> MainActivityViewModel(screenRepo) as T
            ToolbarViewModel::class.java -> ToolbarViewModel(screenRepo) as T
            BrowserAppBarLayoutViewModel::class.java -> BrowserAppBarLayoutViewModel(screenRepo) as T

            BrowserViewModel::class.java -> BrowserViewModel(screenRepo) as T
            NavigationOverlayViewModel::class.java -> NavigationOverlayViewModel(screenRepo) as T

            else -> throw IllegalArgumentException("Unknown modelClass $modelClass")
        }
    }
}