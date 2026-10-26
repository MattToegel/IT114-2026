package M4.MCCS.Part2;

import java.util.ArrayList;
import java.util.List;

/**
 * Carries User records for a requested list or an initial presence snapshot.
 * ServerThread bundles it from the ordinary List supplied by Server;
 * Client reads the records in its matching process method.
 */
public class UserListPayload extends Payload {
    private final List<User> users;

    public UserListPayload(List<User> users) {
        this.users = new ArrayList<>(users);
    }

    public List<User> getUsers() {
        return users;
    }

    @Override
    public String toString() {
        return super.toString() + String.format(" Users: [%s]", getUsers());
    }
}
