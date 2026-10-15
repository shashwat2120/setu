package com.setu.gateway;

import java.net.URI;
import java.util.List;

/**
 * Where a service can be reached right now. Implementations decide what "right now" means (who has announced themselves, who has been
 * failing); the gateway only asks for the candidates, tries them in the order given, and reports the ones that fail.
 */
public interface ServiceDirectory {

    /** Instances worth trying, best first. Empty means the gateway has nowhere to send the request. */
    List<URI> available(String service);

    /** The gateway could not reach this instance; leave it out for a while. */
    void reportFailure(String service, URI instance);
}
