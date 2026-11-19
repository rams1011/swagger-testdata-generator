package com.tx.api.test.generator.studio.service;

import com.tx.api.test.generator.studio.collection.CollectionDocuments;
import com.tx.api.test.generator.studio.collection.StudioEnvironment;
import com.tx.api.test.generator.studio.collection.StudioVariable;
import com.tx.api.test.generator.studio.collection.StudioVariables;
import com.tx.api.test.generator.studio.model.StoredEnvironment;
import com.tx.api.test.generator.studio.model.StoredSpec;
import com.tx.api.test.generator.studio.storage.StudioStorage;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A name that is a secret in one layer (global, collection or environment variables) can't be given a plain value in
 * another layer the same runs use. The plain value would win or stand in for the secret, and it would be stored,
 * exported and shown in results as clear text. Only rows a save adds or changes are checked, so a layer that already
 * holds such a row (from before this rule, or because another layer was made secret later) can still be saved.
 */
public final class SecretNameRules {

    private SecretNameRules() {
    }

    /** Saving an environment's variables: checked against the global and collection variables. */
    public static void requireForEnvironment(StudioStorage storage, String specId, List<StudioVariable> previous,
                                             List<StudioVariable> next) {
        Map<String, List<StudioVariable>> others = new LinkedHashMap<>();
        others.put("the global variables", globals(storage));
        others.put("the collection variables", collection(storage, specId));
        require(previous, next, others);
    }

    /** Saving a collection's variables: checked against the global variables and every environment of the collection. */
    public static void requireForCollection(StudioStorage storage, String specId, List<StudioVariable> previous,
                                            List<StudioVariable> next) {
        Map<String, List<StudioVariable>> others = new LinkedHashMap<>();
        others.put("the global variables", globals(storage));
        environments(storage, specId, "", others);
        require(previous, next, others);
    }

    /** Saving the global variables: checked against every collection's variables and environments in the workspace. */
    public static void requireForGlobals(StudioStorage storage, List<StudioVariable> previous, List<StudioVariable> next) {
        Map<String, List<StudioVariable>> others = new LinkedHashMap<>();
        for (StoredSpec spec : storage.listSpecs()) {
            String label = StringUtils.defaultIfBlank(spec.getName(), spec.getSpecId());
            others.put("the collection variables of " + label, collection(storage, spec.getSpecId()));
            environments(storage, spec.getSpecId(), " of " + label, others);
        }
        require(previous, next, others);
    }

    /**
     * Refuses a plain row in {@code next} that is new or changed since {@code previous}, when another layer declares
     * the same name as a secret.
     */
    static void require(List<StudioVariable> previous, List<StudioVariable> next, Map<String, List<StudioVariable>> others) {
        if (next == null || next.isEmpty()) {
            return;
        }
        Map<String, StudioVariable> before = new LinkedHashMap<>();
        if (previous != null) {
            previous.stream().filter(Objects::nonNull).forEach(row -> before.put(row.key(), row));
        }
        for (StudioVariable row : next) {
            if (row == null || row.secret() || StringUtils.isBlank(row.key())) {
                continue;
            }
            StudioVariable old = before.get(row.key());
            if (old != null && !old.secret() && Objects.equals(old.value(), row.value())) {
                continue;
            }
            for (Map.Entry<String, List<StudioVariable>> layer : others.entrySet()) {
                if (StudioVariables.secretKeys(layer.getValue()).contains(row.key())) {
                    throw new IllegalArgumentException("'" + row.key() + "' is a secret in " + layer.getKey()
                            + ". Mark it secret here too, or use another name, so its value isn't stored as plain text");
                }
            }
        }
    }

    private static List<StudioVariable> globals(StudioStorage storage) {
        return storage.findGlobalVariables().map(StudioVariables::fromDocument).orElse(List.of());
    }

    private static List<StudioVariable> collection(StudioStorage storage, String specId) {
        if (StringUtils.isBlank(specId)) {
            return List.of();
        }
        return storage.findCollectionVariables(specId).map(StudioVariables::fromDocument).orElse(List.of());
    }

    private static void environments(StudioStorage storage, String specId, String suffix,
                                     Map<String, List<StudioVariable>> into) {
        if (StringUtils.isBlank(specId)) {
            return;
        }
        for (StoredEnvironment stored : storage.listEnvironments(specId)) {
            StudioEnvironment environment = CollectionDocuments.parseEnvironment(stored.getDocumentJson());
            List<StudioVariable> variables = environment == null ? List.of() : environment.variableList();
            into.put("the environment " + stored.getName() + suffix, new ArrayList<>(variables));
        }
    }
}
