package com.prguard.analysis;

public enum Verdict {
    MERGEABLE("머지 가능", "🟢"),
    NEEDS_CHANGES("수정 후 머지", "🟠"),
    NOT_RECOMMENDED("머지 비권장", "🔴");

    private final String label;
    private final String emoji;

    Verdict(String label, String emoji) {
        this.label = label;
        this.emoji = emoji;
    }

    public String label() {
        return label;
    }

    public String emoji() {
        return emoji;
    }
}
