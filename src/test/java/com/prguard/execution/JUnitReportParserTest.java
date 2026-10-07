package com.prguard.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prguard.execution.JUnitReportParser.Status;
import com.prguard.execution.JUnitReportParser.TestCase;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class JUnitReportParserTest {

    private static List<TestCase> parse(String xml) {
        return JUnitReportParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parse_gradleReport_readsEveryStatus() {
        List<TestCase> cases = parse("""
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.PostServiceTest" tests="4" failures="1" errors="1" skipped="1">
                  <testcase name="create_ok" classname="com.example.PostServiceTest" time="0.01"/>
                  <testcase name="getPost_missing" classname="com.example.PostServiceTest" time="0.02">
                    <failure message="expected: NotFoundException but was: null" type="AssertionError">stack</failure>
                  </testcase>
                  <testcase name="delete_npe" classname="com.example.PostServiceTest" time="0.01">
                    <error message="NullPointerException">stack</error>
                  </testcase>
                  <testcase name="later" classname="com.example.PostServiceTest" time="0">
                    <skipped/>
                  </testcase>
                </testsuite>""");

        assertThat(cases).extracting(TestCase::name).containsExactly(
                "com.example.PostServiceTest#create_ok", "com.example.PostServiceTest#getPost_missing",
                "com.example.PostServiceTest#delete_npe", "com.example.PostServiceTest#later");
        assertThat(cases).extracting(TestCase::status)
                .containsExactly(Status.PASSED, Status.FAILED, Status.ERROR, Status.SKIPPED);
        assertThat(cases.get(1).message()).isEqualTo("expected: NotFoundException but was: null");
        assertThat(cases.get(1).failed()).isTrue();
        assertThat(cases.get(3).failed()).isFalse();
    }

    @Test
    void parse_externalEntity_isRejected() {
        // PR 코드가 만든 보고서라 외부 엔티티로 서버 파일을 읽으려 할 수 있다
        assertThatThrownBy(() -> parse("""
                <?xml version="1.0"?>
                <!DOCTYPE x [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <testsuite><testcase name="a" classname="X"><failure message="&secret;"/></testcase></testsuite>"""))
                .isInstanceOf(ExecutionException.class);
    }
}
