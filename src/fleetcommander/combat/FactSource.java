package fleetcommander.combat;

/** Something that answers fact names: a ship, an objective, a fleet, a pack. NaN = not available. */
public interface FactSource {
    double get(String name);
}
