package com.ultimateimprovments.mechanics.security.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The GitHub callback page renders values that come from GitHub (the login) and
 * from the config (the linked account). Unescaped, a crafted value could execute
 * script in the player's browser on the auth host.
 */
class GithubAuthServerEscapeTest {

    @Test
    @DisplayName("HTML special characters are escaped in auth pages")
    void escapesHtml() {
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;",
                GithubAuthServer.escapeHtml("<script>alert(1)</script>"));
        assertEquals("&quot;x&quot; &amp; &#39;y&#39;",
                GithubAuthServer.escapeHtml("\"x\" & 'y'"));
    }

    @Test
    @DisplayName("Ordinary GitHub logins survive escaping unchanged")
    void keepsPlainValues() {
        String login = "octocat-2019";
        assertEquals(login, GithubAuthServer.escapeHtml(login));
        assertFalse(GithubAuthServer.escapeHtml(null).contains("null"));
    }
}
