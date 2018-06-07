/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.toolbar

/**
 * An collection of functions that describe the current state of toolbar features.
 */
interface ToolbarStateProvider {
    fun isBackEnabled(): Boolean
    fun isForwardEnabled(): Boolean
    fun getCurrentUrl(): String?
    fun isURLPinned(): Boolean
    fun isHomepage(): Boolean
    fun isRefreshEnabled(): Boolean
}
