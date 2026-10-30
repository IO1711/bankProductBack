package com.bilolbek.bankProductCreation;

/** A real adapter must validate and consume the one-time ticket with the host server. */
public interface HostTicketValidator {
    BankUser validateAndConsume(String ticket);
}
