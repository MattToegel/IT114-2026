package Project.Common;

import java.io.Serializable;

/** Immutable card definition; ID identifies a modifier, not a hand owner. */
public class Card implements Serializable {
    private final int id;
    private final int modifier;

    public Card(int id, int modifier) {
        this.id = id;
        this.modifier = modifier;
    }

    public int getId() { return id; }
    public int getModifier() { return modifier; }

    @Override
    public String toString() {
        return "Card " + id + " (+" + modifier + ")";
    }
}
