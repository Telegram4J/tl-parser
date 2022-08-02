package telegram4j.tl;

import telegram4j.tl.api.TlObject;

import java.util.List;

public interface BaseDocumentFields extends TlObject {

    String mimeType();

    List<DocumentAttribute> attributes();
}
