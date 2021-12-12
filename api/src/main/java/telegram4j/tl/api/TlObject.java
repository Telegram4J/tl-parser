package telegram4j.tl.api;

/** An object of the Telegram API platform. */
public interface TlObject {

    /** CRC32 hash of the tl object declaration string. */
    int identifier();
}
