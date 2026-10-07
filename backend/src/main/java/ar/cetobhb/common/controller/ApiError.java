package ar.cetobhb.common.controller;

import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

/**
 * One row of the error table: HTTP status, stable machine code and a fixed neutral English detail. The detail never
 * interpolates request values or exception messages. {@code logLevel} is the level of the server-side log line, or
 * {@code null} when nothing is logged.
 */
record ApiError(HttpStatus status, String code, String detail, Level logLevel) {
}
