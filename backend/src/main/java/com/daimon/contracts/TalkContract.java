package com.daimon.contracts;

/**
 * The basic conversational contract: given some text (a prompt or a transcript), produce the
 * next utterance as raw text.
 */
public final class TalkContract extends Contract<ContractInputTypeText, ContractInputTypeRawText> {

    public static final String ID = "daimon.contract.talk/v1";
    public static final TalkContract INSTANCE = new TalkContract();

    /** Hard cap on a single utterance, to stop runaway generations from flooding a log. */
    public static final int MAX_OUTPUT_CHARS = 8_000;

    private TalkContract() {
        super(ID, "Produce the next conversational message for the given text context.",
                ContractInputTypeText.class, ContractInputTypeRawText.class);
    }

    @Override
    public void validateOutput(ContractInputTypeRawText output) throws ContractExecutionException {
        super.validateOutput(output);
        if (output.text().isBlank()) {
            throw new ContractExecutionException(ID + ": talk output must not be blank");
        }
        if (output.text().length() > MAX_OUTPUT_CHARS) {
            throw new ContractExecutionException(ID + ": talk output exceeds " + MAX_OUTPUT_CHARS + " characters");
        }
    }
}
