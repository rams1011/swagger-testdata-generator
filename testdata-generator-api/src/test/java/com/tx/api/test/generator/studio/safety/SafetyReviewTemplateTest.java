package com.tx.api.test.generator.studio.safety;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The review suggests rules with the case's path as a template that Settings → Operation rules accepts. */
class SafetyReviewTemplateTest {

    @Test
    void casePathsBecomeRuleTemplates() {
        assertEquals("/users/{userId}", SafetyReviewService.template("/users/{{userId}}"));
        assertEquals("/users/{user_id}/posts", SafetyReviewService.template("/users/{{ user id }}/posts"));
        assertEquals("/users/{id}", SafetyReviewService.template("/users/{id}"));
        assertEquals("/search", SafetyReviewService.template("/search?q={{term}}"));
        assertNull(SafetyReviewService.template("users/1"), "a template starts with /");
        assertNull(SafetyReviewService.template("/a b"));
        assertNull(SafetyReviewService.template(null));
    }
}
