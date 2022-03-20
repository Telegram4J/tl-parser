package telegram4j.tl;

import java.util.List;

public interface UpdatePinnedMessagesFields extends PtsUpdate {

    boolean pinned();

    List<Integer> messages();

    int pts();

    int ptsCount();
}
