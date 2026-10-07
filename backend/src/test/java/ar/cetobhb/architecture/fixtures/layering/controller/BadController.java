package ar.cetobhb.architecture.fixtures.layering.controller;

import ar.cetobhb.architecture.fixtures.layering.repository.BadRepository;

/** Deliberate violation: a controller reaching a repository directly. */
public class BadController {

    private final BadRepository repository = new BadRepository();

    BadRepository repository() {
        return repository;
    }
}
