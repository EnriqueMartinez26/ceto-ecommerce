package ar.cetobhb.admin.controller;

/** The result of an activation or a deactivation: the state it ended in and whether this call changed it. */
record ProductActivationResponse(long id, boolean active, boolean changed) {
}
