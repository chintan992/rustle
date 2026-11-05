package com.chint.audiobook

import androidx.navigation3.runtime.NavKey

/**
 * Handles navigation events (forward and back) by updating [NavigationState]
 * following Unidirectional Data Flow principles.
 */
class Navigator(val state: NavigationState) {

  /**
   * Navigate forward to [route].
   * When [singleTop] is true (default), pushing the same route that is already
   * at the top of the stack is dropped to prevent double-navigation on fast taps.
   */
  fun navigate(route: NavKey, singleTop: Boolean = true) {
    if (singleTop && state.currentRoute == route) {
      return
    }
    state.backStack.add(route)
  }

  /**
   * Navigate back by popping the top destination.
   * Returns true if a destination was popped, false if already at root.
   */
  fun goBack(): Boolean {
    if (state.backStack.size > 1) {
      state.backStack.removeLastOrNull()
      return true
    }
    return false
  }

  /**
   * Replace the entire stack with [route] as the new root.
   */
  fun replaceRoot(route: NavKey) {
    state.backStack.clear()
    state.backStack.add(route)
  }

  /**
   * Finish the onboarding flow.
   * If [VoiceSetupKey] is the root destination, replace it with [LibraryKey].
   * If [VoiceSetupKey] was opened as a sub-destination (e.g. from Settings),
   * pop it back to the caller instead of wiping the backstack.
   */
  fun finishOnboarding() {
    if (state.backStack.size <= 1 && state.currentRoute == VoiceSetupKey) {
      replaceRoot(LibraryKey)
    } else {
      goBack()
    }
  }

  /**
   * Navigate to the Reader for [bookSlug].
   * Drops redundant navigation if the user is already viewing this exact book.
   */
  fun openReader(bookSlug: String, chapterIndex: Int = 1) {
    val current = state.currentRoute
    if (current is ReaderKey && current.bookSlug == bookSlug) {
      return
    }
    navigate(ReaderKey(bookSlug = bookSlug, chapterIndex = chapterIndex))
  }
}
