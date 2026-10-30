package com.bilolbek.bankProductCreation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SigningServiceTest {
    private final DemoEimzoProvider provider=new DemoEimzoProvider();
    private final SigningService signing=new SigningService(provider);
    private final DemoHostProvider directory=new DemoHostProvider();
    private final BankUser creator=directory.user("u-creator");
    private final BankUser head=directory.user("u-head");
    private final String hash="a".repeat(64);

    @Test void acceptsOnlyThePreparedUserDocumentAndSession() {
        String session=String.valueOf(signing.prepare(7,"submit_passport",creator,13,hash).get("sign_session_id"));
        assertThrows(ResponseStatusException.class,()->signing.demoSession(session,head));
        String token=provider.issue(session,creator.pinfl,HexFormat.of().parseHex(hash));
        assertThrows(ResponseStatusException.class,()->signing.verify(session,7,"submit_passport",head,13,hash,token));
        assertThrows(ResponseStatusException.class,()->signing.verify(session,7,"submit_passport",creator,13,"0".repeat(64),token));
        assertEquals(creator.pinfl,signing.verify(session,7,"submit_passport",creator,13,hash,token).pinfl());
        signing.consume(session);
        assertThrows(ResponseStatusException.class,()->signing.verify(session,7,"submit_passport",creator,13,hash,token));
    }

    @Test void rejectsTamperedOrDifferentCertificateSignatures() {
        String session=String.valueOf(signing.prepare(7,"submit_passport",creator,13,hash).get("sign_session_id"));
        byte[] data=HexFormat.of().parseHex(hash);
        String token=provider.issue(session,creator.pinfl,data);
        assertThrows(ResponseStatusException.class,()->signing.verify(session,7,"submit_passport",creator,13,hash,token+"x"));
        String otherPinfl=provider.issue(session,head.pinfl,data);
        assertThrows(ResponseStatusException.class,()->signing.verify(session,7,"submit_passport",creator,13,hash,otherPinfl));
        String otherSession=String.valueOf(signing.prepare(7,"submit_passport",creator,13,hash).get("sign_session_id"));
        assertThrows(ResponseStatusException.class,()->signing.verify(otherSession,7,"submit_passport",creator,13,hash,token));
    }
}
