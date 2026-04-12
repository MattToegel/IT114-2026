package Project.Server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import Project.Common.Card;
import Project.Common.LoggerUtil;

public class Deck {
    private final List<Card> cards = new ArrayList<>();
    private final HashMap<Integer, Card> byId = new HashMap<>();

    private void refillDrawPile() {
        cards.clear();
        cards.addAll(byId.values());
    }

    public void loadCardsFromFile() {
        cards.clear();
        byId.clear();
        // Load cards.txt from classpath so this works from IDE runs and jar packaging.
        Path file = Paths.get("Project", "Server", "cards.txt");
        if (!Files.exists(file)) {
            file = Paths.get("cards.txt");
        }

        try {
            for (String line : Files.readAllLines(file)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length < 2) {
                    continue;
                }

                int id;
                int mod;
                try {
                    id = Integer.parseInt(parts[0].trim());
                    mod = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    continue;
                }

                Card card = new Card(id, mod);
                cards.add(card);
                byId.put(card.getId(), card);
            }
        } catch (IOException e) {
            LoggerUtil.INSTANCE.warning("Unable to load cards file: " + file);
            return;
        }

        refillDrawPile();
    }

    public Card getById(int id) {
        return byId.get(id);
    }

    public List<Card> getCatalogSnapshot() {
        return new ArrayList<>(byId.values());
    }

    public boolean hasCards() {
        return !cards.isEmpty();
    }

    public int drawCardId(Random rng) {
        if (cards.isEmpty()) {
            refillDrawPile();
        }
        if (cards.isEmpty()) {
            return -1;
        }
        int index = rng.nextInt(cards.size());
        Card card = cards.remove(index);
        return card.getId();
    }
}
