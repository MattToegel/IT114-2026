package Project.Common;

import java.io.Serializable;

public class Card implements Serializable {
    private final int id;
    private final int mod;

    public Card(int id, int mod) {
        this.id = id;
        this.mod = mod;
    }

    public int getId() {
        return id;
    }

    public int getMod() {
        return mod;
    }
    public String toString() {
        return "Card{id=" + id + ", mod=" + mod + "}";
    }
}
