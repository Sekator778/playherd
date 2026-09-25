package crowdvsmarket.server.api;

import crowdvsmarket.server.dto.ApiException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.NoSuchElementException;
import org.jboss.logging.Logger;

/** FIXED INTERFACE (1): every RPC failure is {@code {"error":{"code","message"}}} with an
 *  appropriate 4xx/5xx status. */
@Provider
public class ApiExceptionMapper implements ExceptionMapper<Throwable> {

    private static final Logger LOG = Logger.getLogger(ApiExceptionMapper.class);

    private record ErrorBody(ErrorPayload error) {
    }

    private record ErrorPayload(String code, String message) {
    }

    @Override
    public Response toResponse(Throwable e) {
        if (e instanceof ApiException api) {
            return Response.status(api.httpStatus())
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ErrorBody(new ErrorPayload(api.code().name(), api.getMessage())))
                    .build();
        }
        if (e instanceof NoSuchElementException) {
            return Response.status(404).type(MediaType.APPLICATION_JSON)
                    .entity(new ErrorBody(new ErrorPayload("NOT_FOUND", e.getMessage())))
                    .build();
        }
        if (e instanceof IllegalArgumentException) {
            return Response.status(400).type(MediaType.APPLICATION_JSON)
                    .entity(new ErrorBody(new ErrorPayload("BAD_REQUEST", e.getMessage())))
                    .build();
        }
        LOG.error("unhandled RPC failure", e);
        return Response.status(500).type(MediaType.APPLICATION_JSON)
                .entity(new ErrorBody(new ErrorPayload("INTERNAL", "internal error")))
                .build();
    }
}
