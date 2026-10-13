package com.ai.fabric.realapps.handover;

/** Structured model output used to decide whether a second confirmation is needed. */
enum HandoverRequestProvenance {
    EXPLICIT_USER_REQUEST,
    MODEL_RECOMMENDATION
}
