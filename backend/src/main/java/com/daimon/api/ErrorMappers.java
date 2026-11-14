package com.daimon.api;

import com.daimon.api.Dtos.ErrorDto;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Maps domain exceptions to JSON error responses. */
public final class ErrorMappers {

    private ErrorMappers() {
    }

    private static Response error(Response.Status status, Exception e) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorDto(status.name(), e.getMessage()))
                .build();
    }

    /** Invalid input → 400. */
    @Provider
    public static class IllegalArgument implements ExceptionMapper<IllegalArgumentException> {
        @Override
        public Response toResponse(IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST, e);
        }
    }

    /** Operation not valid in the current state → 409. */
    @Provider
    public static class IllegalState implements ExceptionMapper<IllegalStateException> {
        @Override
        public Response toResponse(IllegalStateException e) {
            return error(Response.Status.CONFLICT, e);
        }
    }
}
