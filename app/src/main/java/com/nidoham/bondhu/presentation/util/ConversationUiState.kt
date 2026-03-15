package com.nidoham.bondhu.presentation.util

import com.nidoham.bondhu.presentation.viewmodel.ConversationWithUser
import com.nidoham.server.domain.message.Message

/**
 * All possible UI states for the conversation list screen.
 *
 * The sealed interface guarantees exhaustive `when` branches in the UI layer,
 * meaning the compiler will catch any missing state at build time rather than
 * silently failing at runtime.
 *
 * @since 1.0 Initial definition.
 * @since 1.1 Added [Refreshing], [LoadingMore], and [Error.cachedItems] for
 *            graceful degradation on transient network failures.
 */
sealed interface ConversationUiState {

    /**
     * The very first emission has not yet arrived from Firebase.
     * The UI should show the full skeleton/shimmer list at this stage.
     */
    data object Loading : ConversationUiState

    /**
     * A pull-to-refresh cycle is in progress.
     *
     * The previous list stays on screen so the user never sees a blank flash.
     * Only a top-of-screen progress indicator should appear alongside it.
     *
     * @param items The stale-but-visible list rendered while data refreshes.
     */
    data class Refreshing(val items: List<ConversationWithUser>) : ConversationUiState

    /**
     * A paginated "load more" fetch is running after the user scrolled to
     * the bottom of the list.
     *
     * The current page remains fully rendered; only a footer spinner is added.
     *
     * @param items The already-loaded conversations visible during the fetch.
     */
    data class LoadingMore(val items: List<ConversationWithUser>) : ConversationUiState

    /**
     * The stream resolved successfully but the user has no conversations.
     * Render an empty-state illustration or prompt.
     */
    data object Empty : ConversationUiState

    /**
     * At least one conversation is ready to display.
     *
     * @param items   Enriched, display-ready list of conversation rows.
     * @param hasMore True when a subsequent page exists; drives the footer
     *                spinner's visibility and the scroll-end trigger.
     */
    data class Success(
        val items: List<ConversationWithUser>,
        val hasMore: Boolean = false
    ) : ConversationUiState

    /**
     * An error occurred on the Firebase stream.
     *
     * When [cachedItems] is non-null the screen already has data, so the UI
     * should prefer a non-blocking error banner over a full error page.
     * When it is null the initial load failed and a full error page is correct.
     *
     * @param cause       The exception that caused the failure.
     * @param cachedItems Last successfully emitted list, or null on first-load failure.
     */
    data class Error(
        val cause: Throwable,
        val cachedItems: List<ConversationWithUser>? = null
    ) : ConversationUiState
}

/**
 * All possible UI states for the individual chat / message thread screen.
 *
 * Intentionally kept in a separate sealed interface from [ConversationUiState].
 * The two screens have independent lifecycles, different data shapes, and
 * different ViewModels — conflating them in one type would create a leaky
 * abstraction and make each `when` branch harder to reason about.
 *
 * @since 1.0
 */
sealed interface ChatUiState {

    /**
     * The first page of messages has not yet arrived.
     * Show skeleton chat bubbles to communicate that content is incoming.
     */
    data object Loading : ChatUiState

    /**
     * The conversation document exists but contains no messages yet.
     * Render a "say hi" prompt or similar empty-thread illustration.
     */
    data object Empty : ChatUiState

    /**
     * Older history is being fetched because the user scrolled to the top
     * of the thread.
     *
     * The current page stays visible; show a spinner pinned to the top of
     * the list, not a full-screen indicator.
     *
     * @param messages The page of messages already rendered on screen.
     */
    data class LoadingMore(val messages: List<Message>) : ChatUiState

    /**
     * Messages are loaded and the real-time stream is active.
     *
     * @param messages Full display-ready thread, newest last.
     * @param hasMore  True when earlier history pages are still available.
     */
    data class Success(
        val messages: List<Message>,
        val hasMore: Boolean = false
    ) : ChatUiState

    /**
     * An error occurred while loading or streaming the thread.
     *
     * @param cause          The underlying exception.
     * @param cachedMessages Last known messages, or null if initial load failed.
     */
    data class Error(
        val cause: Throwable,
        val cachedMessages: List<Message>? = null
    ) : ChatUiState
}