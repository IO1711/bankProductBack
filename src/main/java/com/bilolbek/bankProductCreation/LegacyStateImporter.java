package com.bilolbek.bankProductCreation;

import com.bilolbek.bankProductCreation.config.AppProperties;
import java.io.ObjectInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Imports the previous state.bin once, into an empty database, without changing the original file. */
@Component
public class LegacyStateImporter implements ApplicationRunner {
    private final WorkflowRepository repository;
    private final Path legacyFile;
    private final boolean enabled;

    LegacyStateImporter(WorkflowRepository repository, AppProperties properties,
                        @Value("${app.import-legacy-state:true}") boolean enabled) {
        this.repository=repository;
        this.legacyFile=properties.dataDir().resolve("state.bin");
        this.enabled=enabled;
    }

    @Override @Transactional public void run(ApplicationArguments arguments) throws Exception {
        if(!enabled || !Files.isRegularFile(legacyFile) || !repository.all().isEmpty()) return;
        try(ObjectInputStream input=new ObjectInputStream(Files.newInputStream(legacyFile))) {
            DemoStore.State state=(DemoStore.State)input.readObject();
            for(DemoStore.Request source:state.requests) repository.add(convert(source));
        }
    }

    private WorkflowModels.Request convert(DemoStore.Request source) {
        var target=new WorkflowModels.Request();
        target.type=source.type; target.status=source.status; target.creatorId=source.creatorId;
        target.creatorDepartmentId=source.creatorDepartmentId; target.createdAt=source.createdAt;
        target.reason=source.reason; target.cycle=source.cycle;
        target.noticeAssignee=source.noticeAssignee; target.executorDepartmentId=source.executorDepartmentId;
        if(source.receiverDepartments!=null) target.receiverDepartments.addAll(source.receiverDepartments);
        if(source.participantIds!=null) target.participantIds.addAll(source.participantIds);
        Map<Long,Long> documentIds=new HashMap<>();
        for(DemoStore.Document old:source.documents) {
            var document=new WorkflowModels.Document();
            document.id=repository.documentId(); documentIds.put(old.id,document.id);
            document.cycle=old.cycle; document.kind=old.kind; document.name=old.name;
            document.sourcePath=old.sourcePath; document.pdfPath=old.pdfPath; document.sha256=old.sha256;
            document.uploadedBy=old.uploadedBy; document.createdAt=old.createdAt;
            document.size=old.size; document.deleted=old.deleted;
            if(old.signatures!=null) for(DemoStore.Signature previous:old.signatures) {
                var signature=new WorkflowModels.Signature();
                signature.code=previous.code; signature.userId=previous.userId;
                signature.fullName=previous.fullName; signature.position=previous.position;
                signature.at=previous.at; signature.sha256=previous.sha256;
                signature.versionPath=previous.versionPath;
                signature.signedInputSha256=previous.signedInputSha256;
                signature.signerPinfl=previous.signerPinfl;
                signature.certificateSerial=previous.certificateSerial;
                signature.verificationMode=previous.verificationMode; signature.pkcs7=previous.pkcs7;
                document.signatures.add(signature);
            }
            target.documents.add(document);
        }
        for(DemoStore.ReviewTask old:source.reviews) {
            var review=new WorkflowModels.ReviewTask();
            review.id=repository.reviewId(); review.departmentId=old.departmentId;
            review.assignedUserId=old.assignedUserId; review.conclusion=old.conclusion;
            review.status=old.status; review.documentId=remap(documentIds,old.documentId);
            target.reviews.add(review);
        }
        for(DemoStore.Event old:source.history) {
            var event=new WorkflowModels.Event();
            event.at=old.at; event.actor=old.actor; event.action=old.action;
            event.detail=old.detail; event.ip=old.ip; event.cycle=old.cycle;
            event.documentId=remap(documentIds,old.documentId);
            target.history.add(event);
        }
        return target;
    }

    private Long remap(Map<Long,Long> documentIds,Long previousId) {
        return previousId==null?null:documentIds.get(previousId);
    }
}
