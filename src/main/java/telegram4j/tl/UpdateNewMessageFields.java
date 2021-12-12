package telegram4j.tl;

public interface UpdateNewMessageFields extends Update {

    Message message();

    int pts();

    int ptsCount();
}
