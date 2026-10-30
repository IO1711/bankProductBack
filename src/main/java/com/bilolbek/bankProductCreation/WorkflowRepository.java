package com.bilolbek.bankProductCreation;

import java.util.List;

/** Repository for the workflow aggregate; mutations require a surrounding transaction. */
public interface WorkflowRepository {
    WorkflowModels.Request add(WorkflowModels.Request request);
    void save(WorkflowModels.Request request);
    WorkflowModels.Request get(long id);
    WorkflowModels.Request getForUpdate(long id);
    List<WorkflowModels.Request> all();
    WorkflowModels.Request findByDocumentId(long documentId);
    WorkflowModels.Request findBySignatureCode(String code);
    long documentId();
    long reviewId();
}
