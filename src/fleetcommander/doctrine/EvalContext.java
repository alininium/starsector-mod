package fleetcommander.doctrine;

/** What a condition or score is evaluated against. The combat side implements it; tests use a map. */
public interface EvalContext {
    /** Value of a fact (see {@link Facts}); NaN if it is not available right now (comparisons are then false). */
    double fact(String name);

    /** Id of the current battle phase. */
    String phase();

    /** A random roll with probability p, rolled once per battle for each key and remembered. */
    boolean roll(Object key, double p);
}
