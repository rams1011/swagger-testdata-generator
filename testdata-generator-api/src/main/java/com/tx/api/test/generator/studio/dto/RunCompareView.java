package com.tx.api.test.generator.studio.dto;

import java.util.List;

public record RunCompareView(
        RunResultView left,
        RunResultView right,
        List<RunCompareField> differences
) {
}
