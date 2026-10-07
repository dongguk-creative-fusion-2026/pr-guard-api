package com.prguard.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.prguard.agents.LessonService.Lesson;
import com.prguard.agents.LessonService.Row;
import java.util.List;
import org.junit.jupiter.api.Test;

class LessonServiceTest {

    @Test
    void of_matchesRuleIdFirstThenKeywords() {
        assertThat(MistakeType.of("ARITY_MISMATCH", "인자 수가 맞지 않는 호출", "")).isEqualTo(MistakeType.SIGNATURE);
        assertThat(MistakeType.of("LLM_REVIEW", "알림 서버 비밀키가 소스에 하드코딩되어 있습니다", "")).isEqualTo(MistakeType.SECRET);
        assertThat(MistakeType.of("LLM_REVIEW", "/api/users/** 엔드포인트 권한 설정 변경", "")).isEqualTo(MistakeType.AUTH);
        assertThat(MistakeType.of("LLM_REVIEW", "변수 이름이 어색함", "")).isNull();
        // 제목이 본문보다 먼저다: 본문이 곁들여 말한 컬럼 때문에 마이그레이션으로 묶지 않는다
        assertThat(MistakeType.of("LLM_REVIEW", "build.gradle: OkHttp 의존성 좌표 오타", "빌드 실패 가능")).isEqualTo(MistakeType.DEPENDENCY);
        assertThat(MistakeType.of("LLM_REVIEW", "값이 PR 목적과 무관하게 변경됨", "컬럼 길이와도 다름")).isEqualTo(MistakeType.INTENT);
    }

    @Test
    void group_countsDistinctPrsAndMarksRepeated() {
        List<Lesson> lessons = LessonService.group(List.of(
                new Row(8, 64, "LLM_REVIEW", "BLOCKER", "Notify.java", "비밀키가 하드코딩됨", ""),
                new Row(8, 64, "LLM_REVIEW", "MAJOR", "Notify.java", "서버 URL 하드코딩", ""),
                new Row(3, 30, "LLM_REVIEW", "MAJOR", "Config.java", "API key 가 코드에 있음", ""),
                new Row(5, 40, "ARITY_MISMATCH", "BLOCKER", "PostService.java", "인자 수가 맞지 않는 호출", "")));

        assertThat(lessons.get(0).type()).isEqualTo("SECRET");
        assertThat(lessons.get(0).prs()).containsExactly(8, 3);
        assertThat(lessons.get(0).findings()).isEqualTo(3);
        assertThat(lessons.get(0).repeated()).isTrue();
        assertThat(lessons.get(0).worst()).isEqualTo("BLOCKER");
        // 예시는 PR 마다 하나
        assertThat(lessons.get(0).examples()).extracting(LessonService.Example::prNumber).containsExactly(8, 3);
        assertThat(lessons.get(1).type()).isEqualTo("SIGNATURE");
        assertThat(lessons.get(1).repeated()).isFalse();
    }
}
