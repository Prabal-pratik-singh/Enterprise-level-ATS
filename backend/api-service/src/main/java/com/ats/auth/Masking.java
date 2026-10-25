package com.ats.auth;

/** Log lines never carry a full e-mail address: "prabal.singh@gmail.com" → "p***@gmail.com". */
public final class Masking {

    private Masking() {
    }

    public static String email(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        if (at < 1) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
