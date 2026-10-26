package Project.Server;

import Project.Common.Card;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Load a small card catalog and draw from shuffled copies of it. */
final class Deck {
    private final Map<Integer, Card> catalog = new LinkedHashMap<>();
    private final List<Integer> drawPile = new ArrayList<>();
    private final Random random = new Random();

    Deck(Path file) throws IOException {
        for (String line : Files.readAllLines(file)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split(",", -1);
            if (parts.length != 2) {
                throw new IOException("Invalid card line: " + trimmed);
            }
            try {
                int id = Integer.parseInt(parts[0].trim());
                int modifier = Integer.parseInt(parts[1].trim());
                if (id < 1 || modifier < 1 || modifier > 9 || catalog.containsKey(id)) {
                    throw new IOException("Invalid card definition: " + trimmed);
                }
                catalog.put(id, new Card(id, modifier));
            } catch (NumberFormatException e) {
                throw new IOException("Invalid card number: " + trimmed, e);
            }
        }
        if (catalog.isEmpty()) {
            throw new IOException("Card catalog is empty");
        }
        refill();
    }

    List<Card> catalog() { return List.copyOf(catalog.values()); }
    Card find(int id) { return catalog.get(id); }

    int draw() {
        if (drawPile.isEmpty()) {
            refill();
        }
        return drawPile.remove(drawPile.size() - 1);
    }

    private void refill() {
        drawPile.addAll(catalog.keySet());
        Collections.shuffle(drawPile, random);
    }
}
