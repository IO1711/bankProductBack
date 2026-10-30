CREATE SEQUENCE workflow_request_id_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE workflow_document_id_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE workflow_review_id_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE workflow_signature_id_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE workflow_event_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE workflow_requests (
    id BIGINT PRIMARY KEY,
    version BIGINT NOT NULL,
    type VARCHAR(16) NOT NULL,
    status VARCHAR(40) NOT NULL,
    creator_id VARCHAR(100) NOT NULL,
    creator_department_id VARCHAR(100) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    reason TEXT,
    cycle INTEGER NOT NULL,
    notice_assignee VARCHAR(100),
    executor_department_id VARCHAR(100)
);
CREATE INDEX idx_workflow_requests_creator ON workflow_requests(creator_id, id);
CREATE INDEX idx_workflow_requests_status ON workflow_requests(status, id);

CREATE TABLE request_receivers (
    request_id BIGINT NOT NULL REFERENCES workflow_requests(id) ON DELETE CASCADE,
    sort_order INTEGER NOT NULL,
    department_id VARCHAR(100) NOT NULL,
    PRIMARY KEY(request_id, sort_order)
);
CREATE TABLE request_participants (
    request_id BIGINT NOT NULL REFERENCES workflow_requests(id) ON DELETE CASCADE,
    user_id VARCHAR(100) NOT NULL,
    PRIMARY KEY(request_id, user_id)
);

CREATE TABLE workflow_documents (
    id BIGINT PRIMARY KEY,
    request_id BIGINT NOT NULL REFERENCES workflow_requests(id) ON DELETE CASCADE,
    cycle INTEGER NOT NULL,
    kind VARCHAR(20) NOT NULL,
    name VARCHAR(500) NOT NULL,
    source_path TEXT NOT NULL,
    pdf_path TEXT,
    sha256 VARCHAR(64) NOT NULL,
    uploaded_by VARCHAR(100) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    size_bytes BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_workflow_documents_request ON workflow_documents(request_id, id);

CREATE TABLE workflow_signatures (
    id BIGINT PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES workflow_documents(id) ON DELETE CASCADE,
    code VARCHAR(64) NOT NULL UNIQUE,
    user_id VARCHAR(100) NOT NULL,
    full_name VARCHAR(300) NOT NULL,
    position VARCHAR(300) NOT NULL,
    signed_at VARCHAR(40) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    version_path TEXT NOT NULL,
    signed_input_sha256 VARCHAR(64),
    signer_pinfl VARCHAR(14),
    certificate_serial VARCHAR(300),
    verification_mode VARCHAR(40),
    pkcs7 TEXT
);
CREATE INDEX idx_workflow_signatures_document ON workflow_signatures(document_id, id);

CREATE TABLE workflow_reviews (
    id BIGINT PRIMARY KEY,
    request_id BIGINT NOT NULL REFERENCES workflow_requests(id) ON DELETE CASCADE,
    department_id VARCHAR(100) NOT NULL,
    assigned_user_id VARCHAR(100),
    conclusion VARCHAR(20),
    status VARCHAR(30) NOT NULL,
    document_id BIGINT REFERENCES workflow_documents(id)
);
CREATE INDEX idx_workflow_reviews_request ON workflow_reviews(request_id, id);

CREATE TABLE workflow_events (
    id BIGINT PRIMARY KEY,
    request_id BIGINT NOT NULL REFERENCES workflow_requests(id) ON DELETE CASCADE,
    at VARCHAR(40) NOT NULL,
    actor VARCHAR(100) NOT NULL,
    action VARCHAR(100) NOT NULL,
    detail TEXT,
    ip VARCHAR(100),
    cycle INTEGER NOT NULL,
    document_id BIGINT REFERENCES workflow_documents(id)
);
CREATE INDEX idx_workflow_events_request ON workflow_events(request_id, id);
