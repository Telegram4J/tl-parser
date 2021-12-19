package telegram4j.tl;

public interface UpdateEditMessageFields extends PtsUpdate {

    Message message();

    int pts();

    int ptsCount();
}
