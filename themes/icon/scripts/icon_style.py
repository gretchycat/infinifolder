"""Shared generated/runtime icon label style; exported to Kotlin by the packager."""

# Deep colors keep white labels readable over a consistent base pictogram.
LABEL_COLORS = (
    "#a63a3a", "#a65e3a", "#8c6d25", "#497c30", "#2f8066", "#2f7487",
    "#405fa0", "#62509b", "#8a4690", "#99476b", "#6f6860", "#3d7373",
)


def display_label(label: str) -> str:
    return label.strip().lstrip(".").rstrip(":").upper()[:8] or "FILE"


def label_color(label: str) -> str:
    # Java String.hashCode, including UTF-16 code units, so the runtime's
    # label colors agree exactly with static theme artwork.
    value = 0
    encoded = label.upper().encode("utf-16-be")
    for offset in range(0, len(encoded), 2):
        value = (31 * value + int.from_bytes(encoded[offset:offset + 2], "big")) & 0xffffffff
    return LABEL_COLORS[(value & 0x7fffffff) % len(LABEL_COLORS)]
