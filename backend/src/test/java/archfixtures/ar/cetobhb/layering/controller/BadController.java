package archfixtures.ar.cetobhb.layering.controller;

import archfixtures.ar.cetobhb.layering.repository.BadRepository;

/** Deliberate violation: a controller reaching a repository directly. */
public class BadController {

    private final BadRepository repository = new BadRepository();

    BadRepository repository() {
        return repository;
    }
}
