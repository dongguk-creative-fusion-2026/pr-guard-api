package com.prguard.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TraceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void merge_twoJvms_unitesMethodsByNameAndSumsCounts() {
        // 두 JVM 이 같은 함수에 다른 번호를 붙였다
        byte[] a = json("""
                {"methods":["a.FooTest#<init>","a.FooTest#works","a.Foo#bar"],"counts":[1,1,2],
                 "edges":[[1,2,2]],"tests":{"0":[2],"1":[2]}}""");
        byte[] b = json("""
                {"methods":["a.Foo#bar","a.Foo.Inner#baz","a.BarTest#other"],"counts":[3,1,1],
                 "edges":[[2,0,3],[0,1,1]],"tests":{"2":[0,1]}}""");

        Trace trace = Trace.merge(List.of(a, b), mapper);

        assertThat(trace.callsOf("a.Foo#bar")).isEqualTo(5);
        // 테스트 클래스 생성자는 테스트로 치지 않는다
        assertThat(trace.tests()).containsOnlyKeys("a.FooTest#works", "a.BarTest#other");
        assertThat(trace.testsReaching("a.Foo#bar")).containsExactly("a.FooTest#works", "a.BarTest#other");
        assertThat(trace.testsReaching("a.Foo$Inner#baz")).containsExactly("a.BarTest#other");
        assertThat(trace.edges()).hasSize(3);
    }

    @Test
    void merge_brokenPart_isSkipped() {
        Trace trace = Trace.merge(List.of(json("{not json"), json("""
                {"methods":["a.Foo#bar"],"counts":[1],"edges":[],"tests":{}}""")), mapper);

        assertThat(trace.methods()).containsExactly("a.Foo#bar");
    }

    @Test
    void fromJson_roundTrip() throws Exception {
        Trace trace = Trace.merge(List.of(json("""
                {"methods":["a.ATest#t","a.Foo#bar"],"counts":[1,4],"edges":[[0,1,4]],"tests":{"0":[1]}}""")), mapper);

        Trace back = Trace.fromJson(mapper.writeValueAsString(trace.toJson()), mapper);

        assertThat(back.callsOf("a.Foo#bar")).isEqualTo(4);
        assertThat(back.testsReaching("a.Foo#bar")).containsExactly("a.ATest#t");
    }
}
