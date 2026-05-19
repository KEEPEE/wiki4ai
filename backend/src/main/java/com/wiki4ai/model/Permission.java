package com.wiki4ai.model;

/**
 * Permission enum representing the levels of access a user can have within a project.
 * MANAGE includes all permissions plus the ability to grant others.
 */
public enum Permission {
    READ,
    CREATE,
    UPDATE,
    DELETE,
    MANAGE
}
