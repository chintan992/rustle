package com.chint.audiobook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack

/**
 * State holder for Rustle audiobook navigation state.
 *
 * Wraps [rememberNavBackStack] to persist the back stack across configuration
 * changes and process death, while providing clean observability of current routes.
 */
class NavigationState(
  val startRoute: NavKey,
  val backStack: NavBackStack<NavKey>
) {
  val currentRoute: NavKey?
    get() = backStack.lastOrNull()

  val size: Int
    get() = backStack.size

  fun contains(key: NavKey): Boolean = backStack.contains(key)
}

/**
 * Create a [NavigationState] that persists config changes and process death.
 */
@Composable
fun rememberNavigationState(
  startRoute: NavKey
): NavigationState {
  val backStack = rememberNavBackStack(startRoute)
  return remember(backStack, startRoute) {
    NavigationState(
      startRoute = startRoute,
      backStack = backStack
    )
  }
}

