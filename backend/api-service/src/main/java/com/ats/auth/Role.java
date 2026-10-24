package com.ats.auth;

/** Who a user is. The access token carries it; SecurityConfig decides what each role may call. */
public enum Role {
    CANDIDATE,
    RECRUITER,
    ADMIN
}
