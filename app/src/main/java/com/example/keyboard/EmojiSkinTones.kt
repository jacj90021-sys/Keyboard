package com.example.keyboard

/**
 * Skin-tone variants for emoji that accept a tone modifier.
 *
 * The table is keyed by the default form; the five modifiers are appended to
 * the base. Emoji that are not in the table are returned unchanged.
 */
internal object EmojiSkinTones {

    /** Tone modifiers, lightest to darkest. */
    val TONES: List<String> = listOf("🏻", "🏼", "🏽", "🏾", "🏿")

    private val TONE_CAPABLE: Set<String> = setOf(
        "👋", "👍", "👎", "👌", "🤌", "🤏", "✌️", "🤞", "🫰", "🤟", "🤘", "🤙",
        "👈", "👉", "👆", "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "💪", "🦾", "🫶",
        "👏", "🙌", "👐", "🤲", "🤝", "🙏", "💅", "🦵", "🦶", "👂", "🦻", "👃",
        "🧠", "🫀", "🫁", "🦷", "🦴", "👶", "🧑", "👨", "👩", "🧓", "👴", "👵",
        "🏃", "🚶", "🧎", "🧗", "🏊", "🚴", "🚵", "🤸", "🤽", "🤾", "🕺", "💃",
        "🫱", "🫲", "🫳", "🫴", "✍️", "🏂", "🏄", "🏇", "🚣", "🏋️", "🤺",
        "🤼", "⛹️", "🤹", "🧘", "🛌", "🛀", "🚪", "🛋️", "🪑", "🚽", "🛁", "🪒"
    )

    fun isToneCapable(emoji: String): Boolean = emoji in TONE_CAPABLE

    /** The toned forms of [emoji], or an empty list when it has no tones. */
    fun variantsOf(emoji: String): List<String> =
        if (emoji in TONE_CAPABLE) TONES.map { emoji + it } else emptyList()

    /** The modifier at the end of [variant], or null when it is the base form. */
    fun modifierOf(variant: String): String? =
        TONES.firstOrNull { variant.endsWith(it) }

    /** [emoji] with [tone] appended when it supports tones; [emoji] otherwise. */
    fun withTone(emoji: String, tone: String?): String {
        if (tone == null || emoji !in TONE_CAPABLE) return emoji
        return emoji + tone
    }
}