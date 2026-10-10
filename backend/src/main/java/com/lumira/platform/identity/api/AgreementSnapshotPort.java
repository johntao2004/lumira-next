package com.lumira.platform.identity.api;

import java.util.UUID;

/** Settings-owned agreement text and immutable acceptance snapshot contract. */
public interface AgreementSnapshotPort {
    Document current();

    /** Must hold the compatible shared agreement-publication lock in the caller transaction. */
    Snapshot capture(long expectedVersion);

    record Document(long version, String userAgreementMarkdown, String privacyAgreementMarkdown) {
        public boolean complete() {
            return userAgreementMarkdown != null
                    && !userAgreementMarkdown.isBlank()
                    && privacyAgreementMarkdown != null
                    && !privacyAgreementMarkdown.isBlank();
        }
    }

    record Snapshot(
            UUID id,
            long version,
            String userAgreementMarkdown,
            String privacyAgreementMarkdown,
            String contentHash) {}
}
