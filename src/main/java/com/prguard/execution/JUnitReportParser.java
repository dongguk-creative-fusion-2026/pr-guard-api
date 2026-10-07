package com.prguard.execution;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * JUnit XML 보고서(Gradle build/test-results, Maven surefire-reports 의 TEST-*.xml)를 읽는다.
 * 러너가 보낸 파일은 PR 코드가 만든 것이라 믿을 수 없으므로 외부 엔티티 · DTD 를 막는다.
 */
public final class JUnitReportParser {

    private static final int MAX_MESSAGE = 500;

    private JUnitReportParser() {
    }

    public enum Status {
        PASSED, FAILED, ERROR, SKIPPED
    }

    /** @param name 클래스 이름 + "#" + 메서드 이름 */
    public record TestCase(String name, Status status, String message) {

        public boolean failed() {
            return status == Status.FAILED || status == Status.ERROR;
        }
    }

    public static List<TestCase> parse(InputStream xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder builder = f.newDocumentBuilder();
            Document doc = builder.parse(xml);
            NodeList cases = doc.getElementsByTagName("testcase");
            List<TestCase> out = new ArrayList<>();
            for (int i = 0; i < cases.getLength(); i++) {
                Element c = (Element) cases.item(i);
                String name = c.getAttribute("classname") + "#" + c.getAttribute("name");
                Element failure = first(c, "failure");
                Element error = first(c, "error");
                Element skipped = first(c, "skipped");
                if (failure != null) {
                    out.add(new TestCase(name, Status.FAILED, message(failure)));
                } else if (error != null) {
                    out.add(new TestCase(name, Status.ERROR, message(error)));
                } else if (skipped != null) {
                    out.add(new TestCase(name, Status.SKIPPED, null));
                } else {
                    out.add(new TestCase(name, Status.PASSED, null));
                }
            }
            return out;
        } catch (Exception e) {
            throw new ExecutionException("JUnit 보고서를 읽지 못함: " + e.getMessage());
        }
    }

    private static Element first(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    private static String message(Element e) {
        String m = e.getAttribute("message");
        if (m == null || m.isBlank()) {
            m = e.getTextContent();
        }
        m = m == null ? "" : m.strip();
        return m.length() > MAX_MESSAGE ? m.substring(0, MAX_MESSAGE) + "…" : m;
    }
}
