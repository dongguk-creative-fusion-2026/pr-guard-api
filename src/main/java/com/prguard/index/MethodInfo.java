package com.prguard.index;

import java.util.List;
import java.util.Set;

/**
 * 메서드(생성자 포함) 하나.
 *
 * @param id             {@code com.a.Foo#bar(String,int)}. 생성자는 {@code <init>}
 * @param paramTypes     소스에 적힌 파라미터 타입 (제네릭 제거)
 * @param annotations    어노테이션 원문 (예: {@code @PreAuthorize("isAuthenticated()")})
 * @param bodyHash       주석·공백을 뺀 본문의 해시. base/head 비교용
 * @param annotationHash 어노테이션과 접근 제어자의 해시
 * @param implicit       record 컴포넌트 접근자처럼 소스에 없지만 존재하는 메서드
 */
public record MethodInfo(
        String id,
        String typeFqn,
        String name,
        List<String> paramTypes,
        String returnType,
        List<String> annotations,
        Set<String> modifiers,
        String file,
        int startLine,
        int endLine,
        String bodyHash,
        String annotationHash,
        boolean constructor,
        boolean implicit,
        boolean test) {

    public boolean hasAnnotation(String simpleName) {
        return annotations.stream().anyMatch(a -> a.equals("@" + simpleName) || a.startsWith("@" + simpleName + "("));
    }

    /** 이 개수의 인자로 호출할 수 있는가 (가변 인자 고려). */
    public boolean acceptsArgs(int args) {
        int params = paramTypes.size();
        if (params > 0 && paramTypes.get(params - 1).endsWith("...")) {
            return args >= params - 1;
        }
        return params == args;
    }

    public boolean contains(int line) {
        return startLine <= line && line <= endLine;
    }
}
