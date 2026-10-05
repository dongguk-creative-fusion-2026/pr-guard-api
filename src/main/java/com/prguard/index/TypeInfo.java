package com.prguard.index;

import java.util.List;
import java.util.Map;

/**
 * 레포 안에 선언된 타입 하나.
 *
 * @param kind       class / interface / enum / record / annotation
 * @param superTypes extends·implements 대상. 레포 안 타입이면 FQN, 밖이면 소스에 적힌 이름
 * @param fields     필드 이름 → 타입 (레포 안 타입이면 FQN)
 * @param generated  Lombok 처럼 소스에 없는 멤버를 만드는 어노테이션이 붙어 있음
 */
public record TypeInfo(
        String fqn,
        String simpleName,
        String kind,
        String file,
        int line,
        List<String> annotations,
        List<String> superTypes,
        Map<String, String> fields,
        boolean generated,
        boolean test) {
}
