package com.ats.fraud;

import java.util.List;

/** One fraud heuristic. Returns zero or more flags and never throws on odd input. */
public interface TrustCheck {

    List<FraudFlag> check(TrustInput input);
}
