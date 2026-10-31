package com.prguard.review;

import java.util.List;

/** {@link ConflictReviewer} 의 응답. 판정은 하지 않는다. */
public record ConflictReview(List<Item> findings) {

    /**
     * @param method 충돌하는 바뀐 메서드 (입력에 적힌 id 그대로)
     * @param refs   충돌하는 기존 코드 조각 id (C1, C2 …). 비어 있거나 입력에 없는 id 뿐이면 근거를 지어낸 것으로 본다
     */
    public record Item(String severity, String method, String file, Integer line, String title, String message,
                       List<String> refs) {
    }
}
