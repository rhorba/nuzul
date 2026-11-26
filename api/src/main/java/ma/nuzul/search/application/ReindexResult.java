package ma.nuzul.search.application;

/**
 * What a reindex did.
 *
 * @param index the concrete index now behind the alias
 * @param indexed active hotels bulk-indexed into it
 * @param caughtUp hotels re-applied after the swap because they changed while the reindex ran
 */
public record ReindexResult(String index, int indexed, int caughtUp) {}
