package dev.shiftsmith.rest.dto;

/** Minimal error envelope returned with a non-2xx response (e.g. a rejected problem edit). */
public class ApiError {
    public String error;

    public ApiError() {}

    public ApiError(String error) { this.error = error; }
}
