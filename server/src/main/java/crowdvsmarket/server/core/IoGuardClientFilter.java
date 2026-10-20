package crowdvsmarket.server.core;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.ext.Provider;

/** Every REST client request of the application passes {@link IoGuard#check} first (a global provider: the price pipeline's clients
 *  and the chain service's). Does nothing unless the guard is on. */
@Provider
public class IoGuardClientFilter implements ClientRequestFilter {

    @Override
    public void filter(ClientRequestContext request) {
        IoGuard.check("network", request.getMethod() + " " + request.getUri().getHost());
    }
}
