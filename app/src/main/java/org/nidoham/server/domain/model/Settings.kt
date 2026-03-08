package org.nidoham.server.domain.model

/**
 * User-configurable account settings.
 *
 * Permission fields accept one of three string values:
 * - `"everyone"` — any user
 * - `"friends"` — mutual followers only
 * - `"none"` — disabled
 *
 * Stored under `users/{uid}/settings/preferences`.
 */
data class Settings(

    /** Who can send direct messages to this account. */
    val allowMessaging: String = "everyone",

    /** Whether this account's active/online status is visible to others. */
    val showActivityStatus: Boolean = true,

    /** Who can comment on this account's posts. */
    val allowComments: String = "everyone",

    /** Who can reply to this account's stories. */
    val allowStoryReplies: String = "everyone",

    /** Whether two-factor authentication is enabled. */
    val twoFactorEnabled: Boolean = false
)