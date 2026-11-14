package com.daimon.interactions;

import com.daimon.contracts.ContractExecutionException;
import com.daimon.contracts.ContractExecutor;
import com.daimon.contracts.ContractInputTypeRawText;
import com.daimon.contracts.ContractInputTypeText;
import com.daimon.contracts.ContractParticipant;
import com.daimon.contracts.TalkContract;

/** A role that speaks: requires {@link TalkContract}. */
public final class TalkerRole extends Role {

    public TalkerRole(String name) {
        super(name);
    }

    @Override
    public TalkContract requiredContract() {
        return TalkContract.INSTANCE;
    }

    /** Asks {@code participant} for its next utterance given {@code context}. */
    public ContractInputTypeRawText talk(ContractParticipant participant, ContractInputTypeText context)
            throws ContractExecutionException {
        ContractExecutor<ContractInputTypeText, ContractInputTypeRawText> executor = participant
                .executorFor(TalkContract.INSTANCE)
                .orElseThrow(() -> new ContractExecutionException(
                        participant.displayName() + " cannot fulfil " + TalkContract.ID));
        return executor.execute(context);
    }
}
