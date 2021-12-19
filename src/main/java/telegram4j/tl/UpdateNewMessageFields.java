package telegram4j.tl;

public interface UpdateNewMessageFields extends PtsUpdate {

    Message message();

    @Override
    int pts();

    @Override
    int ptsCount();
}
