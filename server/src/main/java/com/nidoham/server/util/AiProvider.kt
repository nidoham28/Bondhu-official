package com.nidoham.server.util

/**
 * Enumerates every AI backend that Bondhu plans to support.
 *
 * [ZAI] is the only active integration. All other values cause
 * [AiMessageSender] to push a "coming soon" message to the conversation
 * on the caller's behalf — no network call is made.
 *
 * @param label   Human-readable name shown in the UI and in DB messages.
 * @param isLive  True only when the backend is wired and callable.
 */
enum class AiProvider(
    val label: String,
    val isLive: Boolean,
) {
    ZAI      (label = "ZAI",      isLive = true),
    GEMINI   (label = "Gemini",   isLive = false),
    GROK     (label = "Grok",     isLive = false),
    CLAUDE   (label = "Claude",   isLive = false),
    CHATGPT  (label = "ChatGPT",  isLive = false),
    DEEPSEEK (label = "DeepSeek", isLive = false),
    KIMI     (label = "Kimi",     isLive = false),
}