package com.bilolbek.bankProductCreation;

import java.io.Serializable;

/** Host supplied identity and organization attributes, also stored in the HTTP session. */
public final class BankUser implements Serializable {
    private static final long serialVersionUID=1L;
    public final String user_id, full_name, pinfl, position, department_id, language;
    public final boolean is_department_head, is_chairman;

    public BankUser(String id,String name,String pinfl,String position,String department,
                    boolean head,boolean chairman,String language) {
        if(id==null || id.isBlank() || name==null || name.isBlank() || pinfl==null || !pinfl.matches("\\d{14}")
                || position==null || position.isBlank() || department==null || department.isBlank()
                || language==null || language.isBlank())
            throw new IllegalArgumentException("Host user data is incomplete or PINFL is invalid");
        this.user_id=id; this.full_name=name; this.pinfl=pinfl; this.position=position;
        this.department_id=department; this.is_department_head=head; this.is_chairman=chairman;
        this.language=language;
    }
}
