package com.prguard.rulemap;

import java.util.List;

/**
 * 기존 코드가 지키는 규칙 하나. 코드에서 바로 뽑은 사실({@link Origin#CODE})이거나, 그 사실이 여러 곳에서 반복되는
 * 관례({@link Origin#CONVENTION})다. LLM 은 쓰지 않는다.
 *
 * @param id        같은 규칙인지 비교하는 키 (예: {@code THROWS|b.PostService#getPost(Long)|NotFoundException})
 * @param subject   규칙의 주인: 메서드 id, 타입 FQN, 필드는 {@code 타입FQN.필드}. 관례는 관례 키
 * @param statement 사람과 LLM 이 읽는 한 문장
 * @param detail    종류별 값 (예외 이름, 검사 메서드, 쿼리, 필드 제약 …)
 * @param support   관례: 따르는 곳 수. 사실이면 1
 * @param total     관례: 해당하는 곳 수. 사실이면 1
 * @param members   관례: 따르는 메서드 · 타입
 * @param outliers  관례: 같은 종류인데 따르지 않는 메서드 · 타입 (사람이 확인할 곳)
 */
public record Rule(
        String id,
        Kind kind,
        Origin origin,
        String subject,
        String statement,
        String detail,
        String file,
        int line,
        int support,
        int total,
        List<String> members,
        List<String> outliers) {

    public enum Kind {
        /** 메서드가 예외를 던진다 */
        THROWS,
        /** 메서드가 null 을 돌려줄 수 있다 */
        RETURNS_NULL,
        /** 메서드가 검사(소유자 확인 · 검증 …)를 부른다 */
        GUARD,
        /** 메서드에 권한 어노테이션이 있다 */
        AUTH,
        /** 쿼리가 정렬한다 */
        ORDERED,
        /** DB 에서 원자적으로 값을 바꾼다 (UPDATE … SET x = x + 1) */
        ATOMIC_UPDATE,
        /** 엔티티 필드 제약 (NOT NULL · 길이 · null 가능) */
        FIELD,
        /** 여러 곳에서 반복되는 관례 */
        CONVENTION
    }

    public enum Origin {
        CODE,
        CONVENTION
    }

    static Rule fact(Kind kind, String subject, String statement, String detail, String file, int line) {
        return new Rule(kind.name() + "|" + subject + "|" + detail, kind, Origin.CODE, subject, statement, detail,
                file, line, 1, 1, List.of(), List.of());
    }

    /** 근거 위치 (파일 이름:줄) */
    public String location() {
        return file == null ? "-" : file.substring(file.lastIndexOf('/') + 1) + (line > 0 ? ":" + line : "");
    }

    /** LLM 에 넘기는 한 줄 */
    public String describe() {
        return statement + (origin == Origin.CONVENTION ? " (관례 " + support + "/" + total + ")" : " (" + location() + ")");
    }
}
