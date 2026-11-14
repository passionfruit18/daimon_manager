package com.daimon.contracts;

import java.util.Objects;

/** A plain, unstructured text payload. Used as both input and output of text contracts. */
public record ContractInputTypeRawText(String text) implements ContractInputTypeText {

    public ContractInputTypeRawText {
        Objects.requireNonNull(text, "text");
    }

    public static ContractInputTypeRawText of(String text) {
        return new ContractInputTypeRawText(text);
    }

    @Override
    public String asText() {
        return text;
    }
}
