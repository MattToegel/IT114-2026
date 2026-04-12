package Project.Sandbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

// Sandbox is a safe place to test small pieces of logic before integrating
// them into the main project code path.
public class Sandbox {
    public static void main(String[] args) {
        // Purpose: verify behavior here first, then copy stable logic into integration code.
        // This keeps debugging simpler because each piece is tested in isolation.

        // Example usage of the classes.
        Grid grid = new Grid();
        grid.setSize(5, 5);
        long seed = 42L;
        // Seed options:
        // - no args: deterministic default (42)
        // - "time"/"timestamp"/"random": current time seed for fresh runs
        // - numeric arg: explicit seed for reproducible tests
        if (args != null && args.length > 0) {
            if ("time".equalsIgnoreCase(args[0]) || "timestamp".equalsIgnoreCase(args[0]) || "random".equalsIgnoreCase(args[0])) {
                seed = System.currentTimeMillis();
            } else {
                try {
                    seed = Long.parseLong(args[0]);
                } catch (NumberFormatException ignored) {
                    // Keep deterministic default seed when argument isn't numeric.
                }
            }
        }
        System.out.println("Using grid seed: " + seed);
        grid.setToRandom(seed);
        grid.print();

        System.out.println("Applying modifier from card...");
        Card card = new Card(1, 3);
        // Apply card effect to center cell and adjacent cells.
        grid.applyModifier(card.getMod(), 2, 2);
        grid.print();

        // Quick check method used by the test harness.
        int oddCount = grid.countOdd(2, 2);
        System.out.println("Number of odd values around (2,2): " + oddCount);

        // Load deck data from classpath resource file.
        Deck deck = new Deck(null);
        deck.loadCardsFromFile();
        System.out.println("Loaded cards:");
        for (Card c : deck.getCards()) {
            System.out.println(c);
        }
    }
}

class Cell {
    private int value;

    private int wrap(int v) {
        // Keeps the value in the 0-9 range, even for negative numbers.
        return Math.floorMod(v, 10);
    }

    public int getValue() {
        return value;
    }

    public void addValue(int v) {
        value = wrap(value + v);
    }

    public void setValue(int v) {
        value = wrap(v);
    }
}

class Grid {
    private Cell[][] cells;

    public void setSize(int x, int y) {
        // Creates an x-by-y grid and initializes every cell.
        cells = new Cell[x][y];
        for (int i = 0; i < x; i++) {
            for (int j = 0; j < y; j++) {
                cells[i][j] = new Cell();
            }
        }
    }

    public void setToZero() {
        // Sets all cells to zero.
        if (cells == null) {
            return;
        }
        for (int i = 0; i < cells.length; i++) {
            for (int j = 0; j < cells[i].length; j++) {
                cells[i][j].setValue(0);
            }
        }
    }

    public void setToRandom(long seed) {
        // Sets all cells to a random value from 0-9 using the provided seed.
        if (cells == null) {
            return;
        }
        Random rand = new Random(seed);
        for (int i = 0; i < cells.length; i++) {
            for (int j = 0; j < cells[i].length; j++) {
                cells[i][j].setValue(rand.nextInt(10));
            }
        }
    }

    public void applyModifier(int mod, int x, int y) {
        cells[x][y].addValue(mod);

        // Up
        if (y > 0) {
            cells[x][y - 1].addValue(mod);
        }
        // Down
        if (y < cells[x].length - 1) {
            cells[x][y + 1].addValue(mod);
        }
        // Left
        if (x > 0) {
            cells[x - 1][y].addValue(mod);
        }
        // Right
        if (x < cells.length - 1) {
            cells[x + 1][y].addValue(mod);
        }
    }

    public int countOdd(int x, int y) {
        // Counts odd values for center cell + its 4 direct neighbors.
        int oddCount = 0;

        if (cells[x][y] != null && cells[x][y].getValue() % 2 != 0) {
            oddCount++;
        }

        // Up
        if (y > 0 && cells[x][y - 1] != null && cells[x][y - 1].getValue() % 2 != 0) {
            oddCount++;
        }
        // Down
        if (y < cells[x].length - 1 && cells[x][y + 1] != null && cells[x][y + 1].getValue() % 2 != 0) {
            oddCount++;
        }
        // Left
        if (x > 0 && cells[x - 1][y] != null && cells[x - 1][y].getValue() % 2 != 0) {
            oddCount++;
        }
        // Right
        if (x < cells.length - 1 && cells[x + 1][y] != null && cells[x + 1][y].getValue() % 2 != 0) {
            oddCount++;
        }

        return oddCount;
    }

    public void print() {
        if (cells == null || cells.length == 0) {
            System.out.println("[]");
            return;
        }
        // Prints one row per line in [n] format for easy console reading.
        for (int y = 0; y < cells[0].length; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < cells.length; x++) {
                row.append("[").append(cells[x][y].getValue()).append("]");
            }
            System.out.println(row);
        }
    }
}

class Card {
    private int id;
    private int mod;

    public Card(int id, int mod) {
        this.id = id;
        this.mod = mod;
    }

    public boolean compareById(int check) {
        return id == check;
    }

    public int getId() {
        return id;
    }
    public int getMod(){
        return mod;
    }
    public String toString() {
        return String.format("Apply %d to target cell and adjacents", mod);
    }
}

class TestUser {
    private HashMap<Integer, Card> cards = new HashMap<Integer, Card>();

    public void addCard(Card c) {
        cards.putIfAbsent(c.getId(), c);
    }

    public void removeCard(Card c) {
        removeCard(c.getId());
    }

    public void removeCard(int id) {
        cards.remove(id);
    }

    public void setCards(List<Card> cards) {
        // Sync hand with incoming list:
        // add any missing cards, and remove cards not in the incoming list.
        if (cards != null) {
            // add new cards not in hand
            for (Card card : cards) {
                if (card == null) {
                    continue;
                }
                this.cards.putIfAbsent(card.getId(), card);
            }
        }
        // remove cards from hand that are missing
        this.cards.entrySet().removeIf(entry -> {
            if (cards == null) {
                return true;
            }
            for (Card card : cards) {
                if (card != null && card.getId() == entry.getKey()) {
                    return false;
                }
            }
            return true;
        });
    }
}

class Deck {
    private List<Card> cards;

    public Deck(List<Card> cards) {
        this.cards = cards;
    }

    public void loadCardsFromFile() {
        // Load cards.txt from classpath so this works from IDE runs and jar packaging.
        if (cards == null) {
            cards = new ArrayList<Card>();
        } else {
            cards.clear();
        }
        // Read a file embedded in the package resources.
        // This works from IDE and from a jar, as long as cards.txt is packaged.
        try (InputStream stream = Deck.class.getResourceAsStream("cards.txt")) {
            if (stream == null) {
                System.err.println("Unable to load cards file from classpath: Project/Sandbox/cards.txt");
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line == null || line.isBlank()) {
                        continue;
                    }

                    String[] parts = line.split(",");
                    if (parts.length < 2) {
                        continue;
                    }

                    try {
                        int id = Integer.parseInt(parts[0].trim());
                        int mod = Integer.parseInt(parts[1].trim());
                        cards.add(new Card(id, mod));
                    } catch (NumberFormatException ignored) {
                        // Skip malformed row.
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Unable to read cards file from classpath: Project/Sandbox/cards.txt");
        }
    }

    public List<Card> getCards() {
        return cards;
    }
}