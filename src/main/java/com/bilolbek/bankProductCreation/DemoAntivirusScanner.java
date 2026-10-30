package com.bilolbek.bankProductCreation;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Fake scanner: accepts ordinary uploads and rejects a marker for repeatable demo tests. */
@Component
@ConditionalOnProperty(name="app.document.mode",havingValue="demo",matchIfMissing=true)
public class DemoAntivirusScanner implements AntivirusScanner {
    private static final byte[] REJECT_MARKER="DEMO_VIRUS".getBytes(StandardCharsets.US_ASCII);

    @Override public void scan(byte[] content,String originalName) {
        outer: for(int start=0;start<=content.length-REJECT_MARKER.length;start++) {
            for(int index=0;index<REJECT_MARKER.length;index++)
                if(content[start+index]!=REJECT_MARKER[index]) continue outer;
            throw WorkflowService.error(HttpStatus.UNPROCESSABLE_ENTITY,"Demo antivirus rejected file");
        }
    }
}
