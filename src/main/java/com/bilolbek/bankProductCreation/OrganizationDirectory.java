package com.bilolbek.bankProductCreation;

import java.util.List;

/** Replace the demo implementation with the bank host's organization API adapter. */
public interface OrganizationDirectory {
    List<BankDepartment> departments();
    BankDepartment department(String id);
    List<BankUser> employees(String departmentId);
    BankUser user(String id);

    default BankUser head(String departmentId) {
        BankDepartment department=department(departmentId);
        return department==null?null:user(department.head_user_id());
    }
}
