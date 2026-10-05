package com.prguard.index;

/**
 * base 와 head 사이에 바뀐 메서드 하나.
 *
 * @param id     head 쪽 id (REMOVED 면 null)
 * @param baseId base 쪽 id (ADDED 면 null)
 * @param line   head 쪽 시작 라인 (REMOVED 면 base 쪽)
 */
public record ChangedMethod(
        Kind kind,
        String id,
        String baseId,
        String typeFqn,
        String name,
        String file,
        int line,
        boolean signatureChanged,
        boolean bodyChanged,
        boolean annotationsChanged,
        boolean test) {

    public enum Kind {
        ADDED,
        REMOVED,
        MODIFIED
    }
}
