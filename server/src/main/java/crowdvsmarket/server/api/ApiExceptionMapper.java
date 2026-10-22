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

    /** Java internals in a message: a dotted class name ({@code crowdvsmarket.server.dto.Enums$Interval}), a nested-class
     *  {@code $}, "No enum constant ...", an exception class. */
    private static final java.util.regex.Pattern INTERNALS =
            java.util.regex.Pattern.compile("(\\b[A-Za-z_][A-Za-z0-9_]*\\.){2,}[A-Za-z$]|\\$|No enum constant|Exception|java\\.");

    /** The message of an {@link IllegalArgumentException} is user-facing where a handler wrote it for a client, but is
     *  Java's own where it comes from {@code Enum.valueOf}, a parser or a library (security review L3): that one never leaves. */
    static String clientSafeMessage(String message) {
        if (message == null || message.isBlank() || message.length() > 300 || INTERNALS.matcher(message).find()) {
            return "invalid request";
        }
        return message;
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
                    .entity(new ErrorBody(new ErrorPayload("BAD_REQUEST", clientSafeMessage(e.getMessage()))))
                    .build();
        }
        // Bug fix (wave-1 finding): a malformed/mistyped JSON request body reaching Jackson
        // through JAX-RS's own message-body reader (e.g. AdminResource's @Consumes JSON param,
        // unlike RpcResource's manual mapper.convertValue — see its own IllegalArgumentException
        // catch in RpcResource#obj) used to fall all the way to the generic 500 below. Confirmed
        // by running it: Quarkus REST's own Jackson body reader (ServerJacksonMessageBodyReader)
        // does not let a JsonProcessingException escape as itself — it already wraps it as a
        // WebApplicationException carrying its own correct 4xx Response (400 for a parse/type
        // failure), which this generic ExceptionMapper<Throwable> used to just discard in favor of
        // the 500 below, since that exception is neither an ApiException nor an
        // IllegalArgumentException. A 4xx WebApplicationException's own Response is preserved
        // (right status), but its entity is replaced with this app's usual `{"error":{...}}`
        // envelope instead of whatever default JAX-RS would render, and with a generic message —
        // its cause's message would otherwise name the Java DTO class (e.g.
        // "crowdvsmarket.server.dto.Betting$PlaceBetRequest") or raw request bytes, never surfaced
        // to a client. A JsonProcessingException reaching this mapper directly and unwrapped
        // (belt-and-suspenders — no known path does this today) gets the same treatment.
        if (e instanceof jakarta.ws.rs.WebApplicationException wae) {
            int status = wae.getResponse() != null ? wae.getResponse().getStatus() : 500;
            if (status >= 400 && status < 500) {
                return Response.status(status).type(MediaType.APPLICATION_JSON)
                        .entity(new ErrorBody(new ErrorPayload("BAD_REQUEST", "invalid request")))
                        .build();
            }
            // else: not a client-error status (5xx, or none at all) — fall through to the
            // generic 500 below like any other unclassified failure.
        } else if (e instanceof com.fasterxml.jackson.core.JsonProcessingException) {
            return Response.status(400).type(MediaType.APPLICATION_JSON)
                    .entity(new ErrorBody(new ErrorPayload("BAD_REQUEST", "invalid request body")))
                    .build();
        }
        LOG.error("unhandled RPC failure", e);
        return Response.status(500).type(MediaType.APPLICATION_JSON)
                .entity(new ErrorBody(new ErrorPayload("INTERNAL", "internal error")))
                .build();
    }
}
