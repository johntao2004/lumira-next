package com.lumira.platform.common;

/** Stable reason codes shared by the worker HTTP server and its disposable parser child. */
public final class OfficeWorkerFailureReasons {
    private OfficeWorkerFailureReasons() {}

    public static String tooLarge(String operation) {
        if (OfficeWorkerProtocol.PREVIEW.equals(operation)
                || OfficeWorkerProtocol.PDF_PAGE.equals(operation)) return "filePreviewTooLarge";
        if (OfficeWorkerProtocol.DICTIONARY.equals(operation)) return "dictionaryDatasetTooLarge";
        return isTemplateOperation(operation)
                ? "documentTemplateTooLarge"
                : "documentWorkbookTooLarge";
    }

    public static String invalid(String operation) {
        if (OfficeWorkerProtocol.PREVIEW.equals(operation)
                || OfficeWorkerProtocol.PDF_PAGE.equals(operation)) return "filePreviewInvalid";
        if (OfficeWorkerProtocol.DICTIONARY.equals(operation)) return "invalidDictionaryDataset";
        return isTemplateOperation(operation)
                ? "documentTemplateInvalid"
                : "documentWorkbookInvalid";
    }

    private static boolean isTemplateOperation(String operation) {
        return OfficeWorkerProtocol.DOCUMENT_INSPECT.equals(operation)
                || OfficeWorkerProtocol.DOCUMENT_PDF.equals(operation);
    }
}
