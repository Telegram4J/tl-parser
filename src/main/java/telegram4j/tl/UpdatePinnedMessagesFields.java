package telegram4j.tl;

import java.util.List;

public interface UpdatePinnedMessagesFields extends PtsUpdate {

    int flags();

    boolean pinned();

    List<Integer> messages();
}
