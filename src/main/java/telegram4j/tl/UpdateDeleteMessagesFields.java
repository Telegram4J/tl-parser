package telegram4j.tl;

import java.util.List;

public interface UpdateDeleteMessagesFields extends Update {

    List<Integer> messages();
}
