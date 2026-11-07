package io.github.warleysr.dechainer.utils

import android.graphics.Rect

object RegionGeometry {
    /**
     * The area from [nodeBounds] down to the end of its scrolling container [area]. If the node has
     * scrolled off the top, the whole container is covered, since everything left in it is below
     * the node; if it is below the container's bottom there is nothing to cover.
     */
    fun below(nodeBounds: Rect, area: Rect): Rect? = when {
        nodeBounds.bottom <= area.top -> Rect(area)
        nodeBounds.top >= area.bottom -> null
        else -> Rect(area.left, maxOf(nodeBounds.top, area.top), area.right, area.bottom)
    }?.takeIf { !it.isEmpty }
}
