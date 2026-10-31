package com.prguard.analysis;

/** Inert protocol fixture. No package installation, network access, or package code execution. */
public final class GuardDogFixtureProcess {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "risk" -> {
                System.err.println("Controlled scanner fixture progress (stderr)");
                System.out.println("""
                        {"issues":1,"errors":{},"results":{},"risk_score":{"score":8.0},
                         "risks":[{"name":"Controlled verification signal","severity":"high",
                         "threat_rule":"verification-fixture","threat_description":"Inert test fixture"}]}
                        """);
            }
            case "timeout" -> Thread.sleep(30_000);
            case "clean" -> System.out.println("{\"issues\":0,\"errors\":{},\"risks\":[]}");
            case "partial" -> System.out.println("""
                    {"issues":1,"errors":{"metadata-rule":"Controlled diagnostic"},
                     "risks":[{"severity":"medium","threat_rule":"verification-fixture",
                     "threat_description":"Inert partial-result fixture"}]}
                    """);
            case "malformed" -> System.out.println("not a JSON scan report");
            case "oversized" -> System.out.println("x".repeat(100_000));
            default -> throw new IllegalArgumentException("Unknown fixture mode");
        }
    }
}
