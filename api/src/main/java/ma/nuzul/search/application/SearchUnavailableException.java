package ma.nuzul.search.application;

/**
 * The search cluster could not serve a request for a reason on its side, not the request's: unreachable, timed out,
 * overloaded (429) or failing (5xx). Worth retrying as long as it takes, so the search indexer waits for the cluster
 * instead of dead-lettering events during an outage. Anything else an index call throws is a fault of the request.
 */
public class SearchUnavailableException extends RuntimeException {

    public SearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
