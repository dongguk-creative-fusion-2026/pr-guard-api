package com.prguard.github;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GitHub 레포 식별자 (owner/name). */
public record RepoRef(String owner, String name) {

    // https://github.com/o/r(.git)(/...) , git@github.com:o/r(.git) , o/r
    private static final Pattern URL = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?github\\.com/([^/\\s]+)/([^/\\s#?]+?)(?:\\.git)?(?:[/#?].*)?$");
    private static final Pattern SSH = Pattern.compile("^git@github\\.com:([^/\\s]+)/([^/\\s]+?)(?:\\.git)?$");
    private static final Pattern SHORT = Pattern.compile("^([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?$");
    private static final Pattern OWNER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]{0,38}$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");

    public static RepoRef parse(String input) {
        String s = input == null ? "" : input.trim();
        for (Pattern p : new Pattern[] {URL, SSH, SHORT}) {
            Matcher m = p.matcher(s);
            if (m.matches() && OWNER.matcher(m.group(1)).matches() && NAME.matcher(m.group(2)).matches()) {
                return new RepoRef(m.group(1), m.group(2));
            }
        }
        throw new IllegalArgumentException("GitHub 레포 주소가 아닙니다: " + input);
    }

    public String fullName() {
        return owner + "/" + name;
    }
}
