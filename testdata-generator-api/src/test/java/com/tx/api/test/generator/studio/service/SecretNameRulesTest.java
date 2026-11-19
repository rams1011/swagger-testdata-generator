package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.collection.StudioVariable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A name that is a secret in one layer can't be given a plain value in another. */
class SecretNameRulesTest {

    private static final Map<String, List<StudioVariable>> COLLECTION_SECRET =
            Map.of("the collection variables", List.of(new StudioVariable("demoToken", null, true)));

    @Test
    void aNewPlainRowNamedLikeASecretElsewhereIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> SecretNameRules.require(
                List.of(), List.of(new StudioVariable("demoToken", "abc", false)), COLLECTION_SECRET));
        assertTrue(refused.getMessage().contains("'demoToken' is a secret in the collection variables"));
    }

    @Test
    void aSecretRowOrAnotherNameIsFine() {
        assertDoesNotThrow(() -> SecretNameRules.require(List.of(),
                List.of(new StudioVariable("demoToken", null, true), new StudioVariable("region", "eu", false)),
                COLLECTION_SECRET));
    }

    @Test
    void turningASecretIntoAPlainRowOrChangingItsValueIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SecretNameRules.require(
                List.of(new StudioVariable("demoToken", null, true)),
                List.of(new StudioVariable("demoToken", "abc", false)), COLLECTION_SECRET));
        assertThrows(IllegalArgumentException.class, () -> SecretNameRules.require(
                List.of(new StudioVariable("demoToken", "old", false)),
                List.of(new StudioVariable("demoToken", "new", false)), COLLECTION_SECRET));
    }

    @Test
    void aRowAlreadyStoredUnchangedDoesNotBlockSavingTheRest() {
        assertDoesNotThrow(() -> SecretNameRules.require(
                List.of(new StudioVariable("demoToken", "old", false)),
                List.of(new StudioVariable("demoToken", "old", false), new StudioVariable("region", "eu", false)),
                COLLECTION_SECRET));
    }
}
