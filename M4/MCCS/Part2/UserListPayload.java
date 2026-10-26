package M4.MCCS.Part2;

import java.util.ArrayList;
import java.util.List;

/** A structured list of users sent by the server. */
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
